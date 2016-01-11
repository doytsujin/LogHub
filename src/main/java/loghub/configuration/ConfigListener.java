package loghub.configuration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr.v4.runtime.ParserRuleContext;

import loghub.RouteBaseListener;
import loghub.RouteParser.ArrayContext;
import loghub.RouteParser.BeanContext;
import loghub.RouteParser.BeanNameContext;
import loghub.RouteParser.BeanValueContext;
import loghub.RouteParser.BooleanLiteralContext;
import loghub.RouteParser.CharacterLiteralContext;
import loghub.RouteParser.FinalpiperefContext;
import loghub.RouteParser.FloatingPointLiteralContext;
import loghub.RouteParser.ForkpiperefContext;
import loghub.RouteParser.InputContext;
import loghub.RouteParser.InputObjectlistContext;
import loghub.RouteParser.IntegerLiteralContext;
import loghub.RouteParser.ObjectContext;
import loghub.RouteParser.OutputContext;
import loghub.RouteParser.OutputObjectlistContext;
import loghub.RouteParser.PipelineContext;
import loghub.RouteParser.PipenodeContext;
import loghub.RouteParser.PipenodeListContext;
import loghub.RouteParser.PiperefContext;
import loghub.RouteParser.PropertyContext;
import loghub.RouteParser.StringLiteralContext;
import loghub.RouteParser.TestContext;
import loghub.RouteParser.TestExpressionContext;
import loghub.configuration.Configuration.PipeJoin;
import loghub.processors.Forker;

class ConfigListener extends RouteBaseListener {

    private static enum StackMarker {
        Test,
        ObjectList,
        PipeNodeList,
        Array;
    };

    static final class Input {
        final List<ObjectDescription> receiver;
        String piperef;
        Input(List<ObjectDescription>receiver, String piperef) {
            this.piperef = piperef;
            this.receiver = receiver;
        }
        @Override
        public String toString() {
            return "(" + receiver.toString() + " -> " + piperef + ")";
        }
    }

    static final class Output {
        final List<ObjectDescription> sender;
        final String piperef;
        Output(List<ObjectDescription>sender, String piperef) {
            this.piperef = piperef;
            this.sender = sender;
        }
        @Override
        public String toString() {
            return "(" + piperef + " -> " +  sender.toString() + ")";
        }
    }

    static interface Processor {};

    static final class Pipeline implements Processor {
        final List<Processor> processors = new ArrayList<>();
    }

    static final class Test implements Processor {
        String test;
        Processor True;
        Processor False;
    }

    static final class PipeRef implements Processor {
        String pipename;
    }

    static final class PipeRefName implements Processor {
        final String piperef;
        private PipeRefName(String piperef) {
            this.piperef = piperef;
        }
    }

    static interface ObjectReference {};

    static final class ObjectWrapped implements ObjectReference {
        final Object wrapped;
        private ObjectWrapped(Object wrapped) {
            this.wrapped = wrapped;
        }
    }

    static class ObjectDescription implements ObjectReference, Iterable<String> {
        final ParserRuleContext ctx;
        final String clazz;
        Map<String, ObjectReference> beans = new HashMap<>();
        ObjectDescription(String clazz, ParserRuleContext ctx) {
            this.clazz = clazz;
            this.ctx = ctx;
        }
        ObjectReference get(String name) {
            return beans.get(name);
        }
        void put(String name, ObjectReference object) {
            beans.put(name, object);
        }
        @Override
        public Iterator<String> iterator() {
            return beans.keySet().iterator();
        }
    };

    static final class ProcessorInstance extends ObjectDescription implements Processor {
        ProcessorInstance(String clazz, ParserRuleContext ctx) {
            super(clazz, ctx);
        }
        ProcessorInstance(ObjectDescription object, ParserRuleContext ctx) {
            super(object.clazz, ctx);
            this.beans = object.beans;
        }
    };

    final Deque<Object> stack = new ArrayDeque<>();

    final Map<String, Pipeline> pipelines = new HashMap<>();
    final List<Input> inputs = new ArrayList<>();
    final List<Output> outputs = new ArrayList<>();
    final Map<String, Object> properties = new HashMap<>();
    final Set<PipeJoin> joins = new HashSet<>();

    private String currentPipeLineName = null;

    @Override
    public void enterPiperef(PiperefContext ctx) {
        stack.push(new PipeRefName(ctx.getText()));
    }

    @Override
    public void enterBeanName(BeanNameContext ctx) {
        stack.push(ctx.getText());
    }

    private void pushLiteral(ParserRuleContext ctx, Object content) {
        // Don't keep literal in a test, they will be managed in groovy
        if(StackMarker.Test.equals(stack.peek())) {
            return;
        }
        if(ctx.getParent().getParent() instanceof BeanValueContext) {
            stack.push(new ObjectWrapped(content));
        } else {
            stack.push(content);
        }
    }

    @Override
    public void enterFloatingPointLiteral(FloatingPointLiteralContext ctx) {
        String content = ctx.FloatingPointLiteral().getText();
        pushLiteral(ctx, new Double(content));
    }

    @Override
    public void enterCharacterLiteral(CharacterLiteralContext ctx) {
        String content = ctx.CharacterLiteral().getText();
        pushLiteral(ctx, content.charAt(0));
    }

    @Override
    public void enterStringLiteral(StringLiteralContext ctx) {
        String content = ctx.StringLiteral().getText();
        // remove "..." and parse escaped char
        content = CharSupport.getStringFromGrammarStringLiteral(content);
        pushLiteral(ctx, content);
    }

    @Override
    public void enterIntegerLiteral(IntegerLiteralContext ctx) {
        String content = ctx.IntegerLiteral().getText();
        pushLiteral(ctx, new Integer(content));
    }

    @Override
    public void enterBooleanLiteral(BooleanLiteralContext ctx) {
        String content = ctx.getText();
        pushLiteral(ctx, new Boolean(content));
    }

    @Override
    public void exitBean(BeanContext ctx) {
        ObjectReference beanValue = (ObjectReference) stack.pop();
        String beanName = (String) stack.pop();
        ObjectDescription beanObject = (ObjectDescription) stack.peek();
        beanObject.put(beanName, beanValue);
    }

    @Override
    public void enterObject(ObjectContext ctx) {
        String qualifiedName = ctx.QualifiedIdentifier().getText();
        ObjectReference beanObject = new ObjectDescription(qualifiedName, ctx);
        stack.push(beanObject);
    }

    @Override
    public void exitPipenode(PipenodeContext ctx) {
        Object o = stack.pop();
        if( ! (o instanceof Processor) ) {
            ObjectDescription object = (ObjectDescription) o;
            ProcessorInstance ti = new ProcessorInstance(object, ctx);
            stack.push(ti);
        } else {
            stack.push(o);
        }
    }

    @Override
    public void exitForkpiperef(ForkpiperefContext ctx) {
        ObjectDescription beanObject = new ObjectDescription(Forker.class.getCanonicalName(), ctx);
        beanObject.put("destination", new ObjectWrapped(ctx.Identifier().getText()));
        ProcessorInstance ti = new ProcessorInstance(beanObject, ctx);
        stack.push(ti);
    }

    @Override
    public void enterPipeline(PipelineContext ctx) {
        currentPipeLineName = ctx.Identifier().getText();
    }

    @Override
    public void exitPipeline(PipelineContext ctx) {
        FinalpiperefContext nextpipe = ctx.finalpiperef();
        if(nextpipe != null) {
            PipeJoin join = new PipeJoin(currentPipeLineName, nextpipe.getText());
            joins.add(join);
            // The PipeRefName was useless
            stack.pop();
        }
        Pipeline pipe;
        if( ! stack.isEmpty()) {
            pipe = (Pipeline) stack.pop();
        } else {
            // Empty pipeline, was not created in exitPipenodeList
            pipe = new Pipeline();
        }
        pipelines.put(currentPipeLineName, pipe);
        currentPipeLineName = null;
    }

    @Override
    public void enterPipenodeList(PipenodeListContext ctx) {
        stack.push(StackMarker.PipeNodeList );
    }

    @Override
    public void exitPipenodeList(PipenodeListContext ctx) {
        Pipeline pipe = new Pipeline();
        while( ! (stack.peek() instanceof StackMarker) ) {
            Processor poped = (Processor)stack.pop();
            pipe.processors.add(0, poped);
        }
        //Remove the marker
        stack.pop();
        stack.push(pipe);
    }

    @Override
    public void exitPiperef(PiperefContext ctx) {
        // In pipenode, part of a pipeline, expect to find a transformer, so transform the name to a PipeRef transformer
        // Other case the name is kept as is
        if(ctx.getParent() instanceof loghub.RouteParser.PipenodeContext) {
            PipeRef piperef = new PipeRef();
            piperef.pipename = ((PipeRefName) stack.pop()).piperef;
            stack.push(piperef);
        }
    }

    @Override
    public void enterTestExpression(TestExpressionContext ctx) {
        stack.push(StackMarker.Test);
    }

    @Override
    public void exitTest(TestContext ctx) {
        Test testTransformer = new Test();
        Processor[] clauses = new Processor[2];

        for(int i=1; ! StackMarker.Test.equals(stack.peek()) ; i-- ) {
            Processor t = (Processor) stack.pop();
            clauses[i] = t;
        };
        stack.pop();
        testTransformer.test = ctx.testExpression().getText();
        testTransformer.True = clauses[0];
        testTransformer.False = clauses[1];
        stack.push(testTransformer);
    }

    @Override
    public void enterInputObjectlist(InputObjectlistContext ctx) {
        stack.push(StackMarker.ObjectList);
    }

    @Override
    public void exitInputObjectlist(InputObjectlistContext ctx) {
        List<ObjectDescription> l = new ArrayList<>();
        while(! StackMarker.ObjectList.equals(stack.peek())) {
            l.add((ObjectDescription) stack.pop());
        }
        stack.pop();
        stack.push(l);
    }

    @Override
    public void enterOutputObjectlist(OutputObjectlistContext ctx) {
        stack.push(StackMarker.ObjectList);
    }

    @Override
    public void exitOutputObjectlist(OutputObjectlistContext ctx) {
        List<ObjectDescription> l = new ArrayList<>();
        while(! StackMarker.ObjectList.equals(stack.peek())) {
            l.add((ObjectDescription) stack.pop());
        }
        stack.pop();
        stack.push(l);
    }

    @Override
    public void exitOutput(OutputContext ctx) {
        PipeRefName piperef;
        @SuppressWarnings("unchecked")
        List<ObjectDescription> senders = (List<ObjectDescription>) stack.pop();
        if(stack.peek() != null && stack.peek() instanceof PipeRefName) {
            piperef = (PipeRefName) stack.pop();
        } else {
            // if no pipe name given, take events from the main pipe
            piperef = new PipeRefName("main");
        }
        Output output = new Output(senders, piperef.piperef);
        outputs.add(output);
    }

    @Override
    public void exitInput(InputContext ctx) {
        PipeRefName piperef;
        if(stack.peek() instanceof PipeRefName) {
            piperef = (PipeRefName) stack.pop();
        } else {
            // if no pipe name given, events are sent to the main pipe
            piperef = new PipeRefName("main");
        }
        @SuppressWarnings("unchecked")
        List<ObjectDescription> receivers = (List<ObjectDescription>) stack.pop();
        Input input = new Input(receivers, piperef.piperef);
        inputs.add(input);
    }

    @Override
    public void exitProperty(PropertyContext ctx) {
        Object value = stack.pop();
        String key = ctx.Identifier().getText();
        properties.put(key, value);
    }

    @Override
    public void enterArray(ArrayContext ctx) {
        stack.push(StackMarker.Array);
    }

    @Override
    public void exitArray(ArrayContext ctx) {
        List<Object> array = new ArrayList<>();
        while(! StackMarker.Array.equals(stack.peek()) ) {
            Object o = stack.pop();
            if(o instanceof ObjectWrapped) {
                o = ((ObjectWrapped) o).wrapped;
            }
            array.add(o);
        }
        stack.pop();
        stack.push(new ObjectWrapped(array.toArray()));
    }

}
