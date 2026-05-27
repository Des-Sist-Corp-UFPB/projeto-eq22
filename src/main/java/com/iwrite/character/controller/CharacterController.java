package com.iwrite.character.controller;

import com.iwrite.character.dto.CharacterRequest;
import com.iwrite.character.dto.CharacterResponse;
import com.iwrite.character.dto.CharacterUpdateRequest;
import com.iwrite.character.service.CharacterService;
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
public class CharacterController {

    private final CharacterService characterService;

    public CharacterController(CharacterService characterService) {
        this.characterService = characterService;
    }

    @GetMapping("/books/{bookId}/characters")
    public List<CharacterResponse> findByBook(@PathVariable UUID bookId) {
        return characterService.findByBook(bookId);
    }

    @PostMapping("/books/{bookId}/characters")
    @ResponseStatus(HttpStatus.CREATED)
    public CharacterResponse create(@PathVariable UUID bookId, @Valid @RequestBody CharacterRequest request) {
        return characterService.create(bookId, request);
    }

    @GetMapping("/characters/{characterId}")
    public CharacterResponse findById(@PathVariable UUID characterId) {
        return characterService.findById(characterId);
    }

    @PatchMapping("/characters/{characterId}")
    public CharacterResponse update(
            @PathVariable UUID characterId,
            @Valid @RequestBody CharacterUpdateRequest request
    ) {
        return characterService.update(characterId, request);
    }

    @DeleteMapping("/characters/{characterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID characterId) {
        characterService.delete(characterId);
    }
}
