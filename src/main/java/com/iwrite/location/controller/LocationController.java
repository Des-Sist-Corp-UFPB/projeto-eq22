package com.iwrite.location.controller;

import com.iwrite.location.dto.LocationRequest;
import com.iwrite.location.dto.LocationResponse;
import com.iwrite.location.dto.LocationUpdateRequest;
import com.iwrite.location.service.LocationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/books/{bookId}/locations")
    public List<LocationResponse> findByBook(@PathVariable UUID bookId) {
        return locationService.findByBook(bookId);
    }

    @PostMapping("/books/{bookId}/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse create(@PathVariable UUID bookId, @Valid @RequestBody LocationRequest request) {
        return locationService.create(bookId, request);
    }

    @GetMapping("/locations/{locationId}")
    public LocationResponse findById(@PathVariable UUID locationId) {
        return locationService.findById(locationId);
    }

    @PatchMapping("/locations/{locationId}")
    public LocationResponse update(
            @PathVariable UUID locationId,
            @Valid @RequestBody LocationUpdateRequest request
    ) {
        return locationService.update(locationId, request);
    }

    @DeleteMapping("/locations/{locationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID locationId) {
        locationService.delete(locationId);
    }
}
