package com.antispam.feedback.web;

import com.antispam.feedback.PersonaPopulationAssembler;
import com.antispam.feedback.PersonaRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The synthetic-user persona endpoints (story 07.01).
 *
 * <p>{@code GET /personas} lists the seeded catalogue — the observable proof that config-driven
 * seeding ran. {@code POST /personas/population} assembles a population from a spec and returns the
 * realized per-persona counts; it is a pure read (no state is mutated), so it is safe to call
 * repeatedly and the same spec always returns the same summary.
 */
@RestController
@RequestMapping("/personas")
public class PersonasController {

    private final PersonaRepository repository;
    private final PersonaPopulationAssembler assembler;

    @Autowired
    public PersonasController(PersonaRepository repository, PersonaPopulationAssembler assembler) {
        this.repository = repository;
        this.assembler = assembler;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PersonaResponse> list() {
        return repository.findAll().stream().map(PersonaResponse::from).toList();
    }

    @PostMapping(path = "/population", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PopulationSummaryResponse assemble(@RequestBody PopulationRequest request) {
        return PopulationSummaryResponse.from(
                request.seed(), assembler.assemble(request.toSpec()));
    }
}
