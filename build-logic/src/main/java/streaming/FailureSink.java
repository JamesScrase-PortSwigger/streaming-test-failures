package streaming;

interface FailureSink extends AutoCloseable {
    FailureSink NONE = new FailureSink() {
        @Override
        public void accept(FailedTest failure) {
        }

        @Override
        public String description() {
            return "nothing (streaming disabled)";
        }
    };

    void accept(FailedTest failure);

    String description();

    @Override
    default void close() {
    }
}
