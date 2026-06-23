package com.iwrite.character.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.character.dto.CharacterRequest;
import com.iwrite.character.dto.CharacterResponse;
import com.iwrite.character.dto.CharacterUpdateRequest;
import com.iwrite.character.entity.Character;
import com.iwrite.character.repository.CharacterRepository;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.item.repository.ItemRepository;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CharacterService {

    private final CharacterRepository characterRepository;
    private final BookService bookService;
    private final CurrentUserProvider currentUserProvider;
    private final SceneRepository sceneRepository;
    private final ItemRepository itemRepository;

    public CharacterService(
            CharacterRepository characterRepository,
            BookService bookService,
            CurrentUserProvider currentUserProvider,
            SceneRepository sceneRepository,
            ItemRepository itemRepository
    ) {
        this.characterRepository = characterRepository;
        this.bookService = bookService;
        this.currentUserProvider = currentUserProvider;
        this.sceneRepository = sceneRepository;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public List<CharacterResponse> findByBook(UUID bookId) {
        bookService.getBook(bookId);
        return characterRepository.findByBook_IdAndBook_Tenant_IdOrderByNameAscIdAsc(
                        bookId,
                        currentUserProvider.tenantId()
                )
                .stream()
                .map(CharacterResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public CharacterResponse findById(UUID characterId) {
        return CharacterResponse.fromEntity(getCharacter(characterId));
    }

    @Transactional
    public CharacterResponse create(UUID bookId, CharacterRequest request) {
        Book book = bookService.getBook(bookId);

        Character character = new Character();
        character.setBook(book);
        character.setName(request.name());
        character.setNickname(request.nickname());
        character.setAge(request.age());
        character.setSex(request.sex());
        character.setNarrativeFunction(request.narrativeFunction());
        character.setGoal(request.goal());
        character.setConflict(request.conflict());
        character.setArc(request.arc());
        character.setPhysicalDescription(request.physicalDescription());
        character.setPersonality(request.personality());
        character.setBiography(request.biography());
        character.setNotes(request.notes());

        return CharacterResponse.fromEntity(characterRepository.save(character));
    }

    @Transactional
    public CharacterResponse update(UUID characterId, CharacterUpdateRequest request) {
        Character character = getCharacter(characterId);
        RequestValidation.rejectBlankWhenPresent("name", request.name());

        if (request.name() != null) {
            character.setName(request.name());
        }
        if (request.nickname() != null) {
            character.setNickname(request.nickname());
        }
        if (request.age() != null) {
            character.setAge(request.age());
        }
        if (request.sex() != null) {
            character.setSex(request.sex());
        }
        if (request.narrativeFunction() != null) {
            character.setNarrativeFunction(request.narrativeFunction());
        }
        if (request.goal() != null) {
            character.setGoal(request.goal());
        }
        if (request.conflict() != null) {
            character.setConflict(request.conflict());
        }
        if (request.arc() != null) {
            character.setArc(request.arc());
        }
        if (request.physicalDescription() != null) {
            character.setPhysicalDescription(request.physicalDescription());
        }
        if (request.personality() != null) {
            character.setPersonality(request.personality());
        }
        if (request.biography() != null) {
            character.setBiography(request.biography());
        }
        if (request.notes() != null) {
            character.setNotes(request.notes());
        }

        return CharacterResponse.fromEntity(character);
    }

    @Transactional
    public void delete(UUID characterId) {
        UUID tenantId = currentUserProvider.tenantId();
        Character character = getCharacterForUpdate(characterId, tenantId);
        if (sceneRepository.existsByPovCharacter_Id(characterId)
                || sceneRepository.existsByParticipantCharacterId(characterId)
                || itemRepository.existsByCurrentOwnerCharacter_Id(characterId)) {
            throw characterReferencedConflict();
        }

        try {
            characterRepository.delete(character);
            characterRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw characterReferencedConflict();
        }
    }

    @Transactional(readOnly = true)
    public Character getCharacter(UUID characterId) {
        return characterRepository.findByIdAndBook_Tenant_Id(characterId, currentUserProvider.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Character not found: " + characterId));
    }

    private Character getCharacterForUpdate(UUID characterId, UUID tenantId) {
        return characterRepository.findByIdAndBookTenantIdForUpdate(characterId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Character not found: " + characterId));
    }

    private ConflictException characterReferencedConflict() {
        return new ConflictException("Character cannot be deleted while it is referenced.");
    }
}
