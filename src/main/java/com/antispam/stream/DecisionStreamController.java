package com.antispam.stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams decisions to the Living Spam Classifier Lab Console live over Server-Sent Events (story 12.01).
 *
 * <p>A browser {@code EventSource} reconnects on its own and replays the id of the last event it
 * saw via the standard {@code Last-Event-ID} header; we hand that to {@link DecisionStream} so the
 * subscription resumes from exactly there. Absent the header (a fresh connection) the client starts
 * from the oldest buffered decision.
 */
@RestController
@RequestMapping("/decisions")
public class DecisionStreamController {

    private final DecisionStream stream;

    @Autowired
    public DecisionStreamController(DecisionStream stream) {
        this.stream = stream;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return stream.subscribe(lastEventId == null ? 0L : lastEventId);
    }
}
