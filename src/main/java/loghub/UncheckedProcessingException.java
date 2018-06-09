package loghub;

public class UncheckedProcessingException extends RuntimeException implements ProcessingException {

    private final Event event;
    
    public UncheckedProcessingException(Event event, String message, Exception root) {
        super(message, root);
        this.event = event;
    }

    public UncheckedProcessingException(Event event, String message) {
        super(message);
        this.event = event;
    }

    /**
     * @return the event
     */
    public Event getEvent() {
        return event;
    }

}
