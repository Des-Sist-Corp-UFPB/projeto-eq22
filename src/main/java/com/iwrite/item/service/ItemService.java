package com.iwrite.item.service;

import com.iwrite.book.entity.Book;
import com.iwrite.book.service.BookService;
import com.iwrite.character.entity.Character;
import com.iwrite.character.service.CharacterService;
import com.iwrite.common.exception.BadRequestException;
import com.iwrite.common.exception.ConflictException;
import com.iwrite.common.exception.ResourceNotFoundException;
import com.iwrite.common.validation.RequestValidation;
import com.iwrite.item.dto.ItemRequest;
import com.iwrite.item.dto.ItemResponse;
import com.iwrite.item.dto.ItemUpdateRequest;
import com.iwrite.item.entity.Item;
import com.iwrite.item.repository.ItemRepository;
import com.iwrite.scene.repository.SceneRepository;
import com.iwrite.user.context.CurrentUserProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final BookService bookService;
    private final CharacterService characterService;
    private final CurrentUserProvider currentUserProvider;
    private final SceneRepository sceneRepository;

    public ItemService(
            ItemRepository itemRepository,
            BookService bookService,
            CharacterService characterService,
            CurrentUserProvider currentUserProvider,
            SceneRepository sceneRepository
    ) {
        this.itemRepository = itemRepository;
        this.bookService = bookService;
        this.characterService = characterService;
        this.currentUserProvider = currentUserProvider;
        this.sceneRepository = sceneRepository;
    }

    @Transactional(readOnly = true)
    public List<ItemResponse> findByBook(UUID bookId) {
        bookService.getBook(bookId);
        return itemRepository.findByBook_IdAndBook_Tenant_IdOrderByNameAscIdAsc(
                        bookId,
                        currentUserProvider.tenantId()
                )
                .stream()
                .map(ItemResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ItemResponse findById(UUID itemId) {
        return ItemResponse.fromEntity(getItem(itemId));
    }

    @Transactional
    public ItemResponse create(UUID bookId, ItemRequest request) {
        Book book = bookService.getBook(bookId);
        Character owner = findOwnerForBook(bookId, request.currentOwnerCharacterId());

        Item item = new Item();
        item.setBook(book);
        item.setName(request.name());
        item.setType(request.type());
        item.setDescription(request.description());
        item.setOrigin(request.origin());
        item.setCurrentOwnerCharacter(owner);
        item.setNarrativeImportance(request.narrativeImportance());
        item.setNotes(request.notes());

        return ItemResponse.fromEntity(itemRepository.save(item));
    }

    @Transactional
    public ItemResponse update(UUID itemId, ItemUpdateRequest request) {
        Item item = getItem(itemId);
        RequestValidation.rejectBlankWhenPresent("name", request.name());
        Character owner = request.isCurrentOwnerCharacterIdPresent()
                ? findOwnerForBook(item.getBook().getId(), request.currentOwnerCharacterId())
                : item.getCurrentOwnerCharacter();

        if (request.name() != null) {
            item.setName(request.name());
        }
        if (request.type() != null) {
            item.setType(request.type());
        }
        if (request.description() != null) {
            item.setDescription(request.description());
        }
        if (request.origin() != null) {
            item.setOrigin(request.origin());
        }
        if (request.isCurrentOwnerCharacterIdPresent()) {
            item.setCurrentOwnerCharacter(owner);
        }
        if (request.narrativeImportance() != null) {
            item.setNarrativeImportance(request.narrativeImportance());
        }
        if (request.notes() != null) {
            item.setNotes(request.notes());
        }

        return ItemResponse.fromEntity(item);
    }

    @Transactional
    public void delete(UUID itemId) {
        UUID tenantId = currentUserProvider.tenantId();
        Item item = getItemForUpdate(itemId, tenantId);
        if (sceneRepository.existsByItemId(itemId)) {
            throw itemReferencedConflict();
        }

        try {
            itemRepository.delete(item);
            itemRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw itemReferencedConflict();
        }
    }

    @Transactional(readOnly = true)
    public Item getItem(UUID itemId) {
        return itemRepository.findByIdAndBook_Tenant_Id(itemId, currentUserProvider.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemId));
    }

    private Item getItemForUpdate(UUID itemId, UUID tenantId) {
        return itemRepository.findByIdAndBookTenantIdForUpdate(itemId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + itemId));
    }

    private Character findOwnerForBook(UUID bookId, UUID characterId) {
        if (characterId == null) {
            return null;
        }

        Character character = characterService.getCharacter(characterId);
        if (!character.getBook().getId().equals(bookId)) {
            throw new BadRequestException("currentOwnerCharacterId must belong to the same book");
        }

        return character;
    }

    private ConflictException itemReferencedConflict() {
        return new ConflictException("Item cannot be deleted while it is referenced.");
    }
}
