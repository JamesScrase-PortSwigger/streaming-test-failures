package streaming;

record Annotation(FailedTest failure, Level level, String title) {
    enum Level {
        WARNING("warning"),
        FAILURE("failure");

        private final String apiValue;

        Level(String apiValue) {
            this.apiValue = apiValue;
        }

        String apiValue() {
            return apiValue;
        }
    }
}
