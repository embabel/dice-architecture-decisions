package example.architecture;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("decisions")
record DecisionProperties(@DefaultValue("0.8") double minimumEvidenceConfidence) {

    DecisionProperties {
        if (!Double.isFinite(minimumEvidenceConfidence)
                || minimumEvidenceConfidence < 0 || minimumEvidenceConfidence > 1) {
            throw new IllegalArgumentException("Minimum evidence confidence must be between 0 and 1");
        }
    }
}
