package com.antispam.seed.web;

import com.antispam.seed.SeedSampleService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists labeled seed samples for the analyzer's sample picker (story 01.05): a
 * viewer can pick a known ham/spam/phish and analyse it by id without pasting.
 * Read-only over the seed corpus loaded in 01.03.
 */
@RestController
public class SeedSampleController {

    private final SeedSampleService seedSampleService;

    @Autowired
    public SeedSampleController(SeedSampleService seedSampleService) {
        this.seedSampleService = seedSampleService;
    }

    @GetMapping(value = "/seed/samples", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SeedSampleResponse> samples(
            @RequestParam(name = "perLabel", defaultValue = "4") int perLabel) {
        return seedSampleService.samples(perLabel).stream()
                .map(SeedSampleResponse::from)
                .toList();
    }
}
