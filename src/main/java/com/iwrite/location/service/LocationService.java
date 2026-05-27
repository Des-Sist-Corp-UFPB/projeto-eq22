package com.iwrite.location.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.location.dto.LocationRequest;
import com.iwrite.location.dto.LocationResponse;
import com.iwrite.location.dto.LocationUpdateRequest;
import com.iwrite.location.entity.Location;
import com.iwrite.location.repository.LocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class LocationService {

    private final LocationRepository locationRepository;
    private final BookService bookService;

    public LocationService(LocationRepository locationRepository, BookService bookService) {
        this.locationRepository = locationRepository;
        this.bookService = bookService;
    }

    @Transactional(readOnly = true)
    public List<LocationResponse> findByBook(UUID bookId) {
        bookService.getBook(bookId);
        return locationRepository.findByBookIdOrderByNameAscIdAsc(bookId)
                .stream()
                .map(LocationResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public LocationResponse findById(UUID locationId) {
        return LocationResponse.fromEntity(getLocation(locationId));
    }

    @Transactional
    public LocationResponse create(UUID bookId, LocationRequest request) {
        Book book = bookService.getBook(bookId);

        Location location = new Location();
        location.setBook(book);
        location.setName(request.name());
        location.setType(request.type());
        location.setDescription(request.description());
        location.setHistoryContext(request.historyContext());
        location.setNarrativeImportance(request.narrativeImportance());
        location.setNotes(request.notes());

        return LocationResponse.fromEntity(locationRepository.save(location));
    }

    @Transactional
    public LocationResponse update(UUID locationId, LocationUpdateRequest request) {
        Location location = getLocation(locationId);
        RequestValidation.rejectBlankWhenPresent("name", request.name());

        if (request.name() != null) {
            location.setName(request.name());
        }
        if (request.type() != null) {
            location.setType(request.type());
        }
        if (request.description() != null) {
            location.setDescription(request.description());
        }
        if (request.historyContext() != null) {
            location.setHistoryContext(request.historyContext());
        }
        if (request.narrativeImportance() != null) {
            location.setNarrativeImportance(request.narrativeImportance());
        }
        if (request.notes() != null) {
            location.setNotes(request.notes());
        }

        return LocationResponse.fromEntity(locationRepository.saveAndFlush(location));
    }

    @Transactional
    public void delete(UUID locationId) {
        Location location = getLocation(locationId);
        locationRepository.delete(location);
    }

    @Transactional(readOnly = true)
    public Location getLocation(UUID locationId) {
        return locationRepository.findById(locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location not found: " + locationId));
    }
}
