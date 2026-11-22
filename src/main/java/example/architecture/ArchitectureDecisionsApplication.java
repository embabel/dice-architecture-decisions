package example.architecture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@SpringBootApplication
public class ArchitectureDecisionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchitectureDecisionsApplication.class, args);
    }
}

@RestController
class DecisionController {

    private final DecisionNotebook notebook = new DecisionNotebook();

    @GetMapping("/api/decision")
    DecisionNotebook.DecisionView inspect(@RequestParam(defaultValue = "review") String revision) {
        if (!revision.equals("review") && !revision.equals("adopted")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown decision revision");
        }
        return notebook.inspect(revision);
    }
}
