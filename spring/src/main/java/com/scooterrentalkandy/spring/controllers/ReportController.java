package com.scooterrentalkandy.spring.controllers;


import java.time.LocalDate;
import java.util.Map;
import com.scooterrentalkandy.spring.dto.ReportDtos.Dashboard;
import com.scooterrentalkandy.spring.dto.ReportDtos.Summary;
import com.scooterrentalkandy.spring.services.ReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/api/admin/dashboard")
    public Dashboard dashboard() {
        return reports.dashboard();
    }

    @GetMapping("/api/admin/reports/summary")
    public Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return reports.summary(start, end);
    }

    @GetMapping("/api/admin/reports/bookings.csv")
    public ResponseEntity<String> bookingsCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bookings-" + from + "-to-" + to + ".csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(reports.bookingsCsv(from, to));
    }
}
