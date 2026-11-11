package com.expplatform.api;

import com.expplatform.service.ExperimentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExperimentController {

    private final ExperimentService experiments;

    public ExperimentController(ExperimentService experiments) {
        this.experiments = experiments;
    }

    @GetMapping("/experiments")
    public List<Map<String, Object>> list() {
        return experiments.list();
    }

    @GetMapping("/experiments/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return experiments.detail(id);
    }

    @PostMapping("/experiments")
    public Map<String, Object> create(@RequestBody ExperimentService.CreateExperimentRequest req) {
        return experiments.create(req);
    }

    @PostMapping("/experiments/{id}/transition")
    public Map<String, Object> transition(@PathVariable String id, @RequestBody ExperimentService.TransitionRequest req) {
        return experiments.transition(id, req);
    }

    @PostMapping("/experiments/{id}/analyze")
    public Map<String, Object> analyze(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        return experiments.analyze(id, body == null ? null : body.get("triggeredBy"));
    }

    @PostMapping("/experiments/{id}/decisions")
    public Map<String, Object> decide(@PathVariable String id, @RequestBody ExperimentService.DecisionRequest req) {
        return experiments.decide(id, req);
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable String runId) {
        return experiments.run(runId);
    }

    @GetMapping("/metrics")
    public List<Map<String, Object>> metrics() {
        return experiments.metrics();
    }

    @GetMapping("/design/sample-size")
    public Map<String, Object> sampleSize(@RequestParam double baseline, @RequestParam double mde,
                                          @RequestParam(defaultValue = "0.05") double alpha,
                                          @RequestParam(defaultValue = "0.8") double power,
                                          @RequestParam(defaultValue = "2") int arms,
                                          @RequestParam(defaultValue = "50") double minSharePct) {
        return experiments.sampleSize(baseline, mde, alpha, power, arms, minSharePct);
    }
}
