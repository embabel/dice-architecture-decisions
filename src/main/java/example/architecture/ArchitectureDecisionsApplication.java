package example.architecture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

@SpringBootApplication
@EnableConfigurationProperties(DecisionProperties.class)
public class ArchitectureDecisionsApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(ArchitectureDecisionsApplication.class);
        var openAiKey = System.getenv("OPENAI_API_KEY");
        var configuredProfiles = System.getenv("SPRING_PROFILES_ACTIVE") != null
                || System.getProperty("spring.profiles.active") != null
                || Arrays.stream(args).anyMatch(arg -> arg.startsWith("--spring.profiles.active="));
        if (!configuredProfiles && openAiKey != null && !openAiKey.isBlank()) {
            application.setAdditionalProfiles("live");
        }
        application.run(args);
    }
}

@RestController
class DecisionController {

    private final DecisionRecord record;

    DecisionController(DecisionRecord record) {
        this.record = record;
    }

    @GetMapping("/api/decision")
    DecisionNotebook.DecisionView inspect(@RequestParam(defaultValue = "review") String revision,
                                          @RequestParam(defaultValue = "false") boolean replayDrill) {
        if (!revision.equals("review") && !revision.equals("adopted")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown decision revision");
        }
        return record.inspect(revision, replayDrill);
    }

    @PostMapping("/api/decision/versions")
    @ResponseStatus(HttpStatus.CREATED)
    DecisionRecord.Entry record(@RequestBody DecisionRecord.Change change) {
        return record.write(change);
    }
}
