package loghub;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import loghub.configuration.Properties;

public class TestEvent {

    private static Logger logger ;

    private static class Looper extends Processor {

        @Override
        public boolean process(Event event) throws ProcessorException {
            event.appendProcessor(this);
            return true;
        }

        @Override
        public String getName() {
            return "Looper";
        }

    }

    @BeforeClass
    static public void configure() throws IOException {
        Tools.configure();
        logger = LogManager.getLogger();
        LogUtils.setLevel(logger, Level.TRACE, "loghub");
    }

    @Test
    public void TestPath() {
        Event e = Tools.getEvent();
        e.applyAtPath((i, j, k) -> i.put(j, k), new String[]{"a", "b", "c"}, 1, true);
        e.put("d", 2);
        e.applyAtPath((i, j, k) -> i.put(j, k), new String[]{"e"}, 3, true);
        Assert.assertEquals("wrong number of keys", 3, e.keySet().size());
        Assert.assertEquals("Didn't resolve the path correctly",  1, e.applyAtPath((i, j, k) -> i.get(j), new String[]{"a", "b", "c"}, null));
        Assert.assertEquals("Didn't resolve the path correctly",  1, e.applyAtPath((i, j, k) -> i.remove(j), new String[]{"a", "b", "c"}, null));
        Assert.assertEquals("Didn't resolve the path correctly",  2, e.applyAtPath((i, j, k) -> i.get(j), new String[]{"d"}, null) );
        Assert.assertEquals("Didn't resolve the path correctly",  3, e.get("e") );
    }

    @Test
    public void TestForkable() {
        Event e = Tools.getEvent();
        e.put("key", "value");
        Event e2 = e.duplicate();
        Assert.assertEquals("cloned value not found", e.get("key"), e2.get("key"));
        e.end();
        e2.end();
    }

    @Test
    public void TestLoop() {
        Map<String, Object> conf = new HashMap<>();
        conf.put("maxSteps", 5);
        Properties props = new Properties(conf);
        Event e = Event.emptyTestEvent(ConnectionContext.EMPTY);
        e.appendProcessor(new Looper());
        EventsProcessor ep = new EventsProcessor(props.mainQueue, props.outputQueues, props.namedPipeLine, props.maxSteps, props.repository);
        //e.appendProcessor(p);
        Processor processor;
        int numsteps = 0;
        int loop = 0;
        while ((processor = e.next()) != null) {
            logger.debug("doing step");
            loop++;
            if (ep.process(e, processor) != 0) {
                break;
            };
            Assert.assertTrue("Not counting processing", e.stepsCount() > numsteps);
            Assert.assertTrue("Not stopping processing", e.stepsCount() <= props.maxSteps);
            Assert.assertTrue("Not stopping processing", e.stepsCount() <= loop);
            numsteps = e.stepsCount();
        }
        Assert.assertTrue("Breaking early", e.stepsCount() >= props.maxSteps);
        e.end();
    }

}
