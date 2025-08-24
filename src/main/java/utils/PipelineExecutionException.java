package utils; // o il tuo package di eccezioni


public class PipelineExecutionException extends Exception {

    public PipelineExecutionException(String message) {
        super(message);
    }

    public PipelineExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
