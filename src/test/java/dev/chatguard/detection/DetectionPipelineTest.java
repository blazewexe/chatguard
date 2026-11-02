package dev.chatguard.detection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DetectionPipelineTest {

    private final UUID dummyUuid = UUID.randomUUID();

    @Test
    @DisplayName("Detection pipeline executes all detectors within budget")
    void testPipelineExecution() {
        ChatDetector detector1 = (raw, norm, strip, id) -> DetectionResult.clean(ViolationType.ADVERTISING);
        ChatDetector detector2 = (raw, norm, strip, id) -> DetectionResult.clean(ViolationType.SPAM);

        DetectionPipeline pipeline = new DetectionPipeline(50, detector1, detector2);
        List<DetectionResult> results = pipeline.analyze("Test message", dummyUuid);

        assertEquals(2, results.size());
        assertFalse(results.get(0).violated());
        assertFalse(results.get(1).violated());
    }

    @Test
    @DisplayName("Detection pipeline captures violations from detectors")
    void testPipelineCapturesViolation() {
        ChatDetector detector1 = (raw, norm, strip, id) -> DetectionResult.violation(ViolationType.ADVERTISING, 0.95, "IP found");
        ChatDetector detector2 = (raw, norm, strip, id) -> DetectionResult.clean(ViolationType.SPAM);

        DetectionPipeline pipeline = new DetectionPipeline(50, detector1, detector2);
        List<DetectionResult> results = pipeline.analyze("192.168.1.1", dummyUuid);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(DetectionResult::violated));
    }
}
