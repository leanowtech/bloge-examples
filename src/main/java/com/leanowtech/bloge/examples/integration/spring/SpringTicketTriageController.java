package com.leanowtech.bloge.examples.integration.spring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo HTTP facade for the Spring Boot starter example.
 */
@RestController
@RequestMapping("/api/bloge/tickets")
public class SpringTicketTriageController {

    private final SpringTicketTriageService triageService;

    public SpringTicketTriageController(SpringTicketTriageService triageService) {
        this.triageService = triageService;
    }

    @GetMapping("/triage")
    public SpringTicketTriageService.TicketTriageResponse triage(
            @RequestParam("ticketId") String ticketId,
            @RequestParam("message") String message,
            @RequestParam(name = "customerTier", defaultValue = "standard") String customerTier) {
        return triageService.triage(ticketId, message, customerTier);
    }
}
