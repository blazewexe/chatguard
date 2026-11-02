package dev.chatguard.detection;

/**
 * Types of chat violations that ChatGuard can detect.
 */
public enum ViolationType {

    ADVERTISING("Advertising"),
    SPAM("Spam"),
    TOXICITY("Toxicity");

    private final String displayName;

    ViolationType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

