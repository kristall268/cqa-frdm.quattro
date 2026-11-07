package com.cqafrdm.checklist;

import com.cqafrdm.common.NotFoundException;
import com.cqafrdm.common.ValidationException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChecklistService {

    private final ChecklistVersionRepository repository;

    public ChecklistService(ChecklistVersionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ChecklistVersionDto> list() {
        return repository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(ChecklistVersionDto::from).toList();
    }

    @Transactional(readOnly = true)
    public ChecklistVersionDto get(Long id) {
        return repository.findById(id).map(ChecklistVersionDto::from)
                .orElseThrow(() -> new NotFoundException("Версия чек-листа не найдена: " + id));
    }

    @Transactional(readOnly = true)
    public ChecklistVersion getEntity(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Версия чек-листа не найдена: " + id));
    }

    @Transactional
    public ChecklistVersionDto create(ChecklistRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ValidationException("Не указано имя чек-листа");
        }
        validate(request.criteria());
        String name = request.name().trim();
        int next = repository.maxVersion(name) + 1;
        ChecklistVersion saved = repository.save(new ChecklistVersion(name, next, request.criteria()));
        return ChecklistVersionDto.from(saved);
    }

    static void validate(List<Criterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            throw new ValidationException("Список критериев пуст");
        }
        Set<String> ids = new HashSet<>();
        boolean hasWeighted = false;
        for (Criterion c : criteria) {
            if (c == null || c.id() == null || c.id().isBlank()) {
                throw new ValidationException("У критерия не указан id");
            }
            if (!ids.add(c.id())) {
                throw new ValidationException("Повторяющийся id критерия: " + c.id());
            }
            if (c.title() == null || c.title().isBlank()) {
                throw new ValidationException("У критерия " + c.id() + " не указано название");
            }
            if (c.type() == null) {
                throw new ValidationException("У критерия " + c.id() + " не указан тип");
            }
            BigDecimal w = c.weight() == null ? BigDecimal.ZERO : c.weight();
            switch (c.type()) {
                case BINARY -> {
                    requirePositive(c, w);
                    hasWeighted = true;
                }
                case SCALE -> {
                    requirePositive(c, w);
                    validateScale(c);
                    hasWeighted = true;
                }
                case FLAG -> {
                    if (w.signum() != 0) {
                        throw new ValidationException("У критерия-флага " + c.id() + " вес должен быть 0");
                    }
                    if (c.naAllowed()) {
                        throw new ValidationException("Для флага " + c.id() + " N/A недопустим");
                    }
                }
            }
            if (c.naAllowed() && (c.naCondition() == null || c.naCondition().isBlank())) {
                throw new ValidationException("Для критерия " + c.id() + " с N/A нужно указать условие N/A");
            }
        }
        if (!hasWeighted) {
            throw new ValidationException("Нужен хотя бы один критерий с весом");
        }
    }

    private static void requirePositive(Criterion c, BigDecimal w) {
        if (w.signum() <= 0) {
            throw new ValidationException("Вес критерия " + c.id() + " должен быть больше 0");
        }
    }

    private static void validateScale(Criterion c) {
        if (c.scale() == null || c.scale().size() < 2) {
            throw new ValidationException("У шкального критерия " + c.id() + " нужно минимум 2 ступени");
        }
        Set<Integer> values = new HashSet<>();
        int max = Integer.MIN_VALUE;
        for (ScaleStep s : c.scale()) {
            if (s.value() < 0) {
                throw new ValidationException("Ступень шкалы " + c.id() + " не может быть отрицательной");
            }
            if (!values.add(s.value())) {
                throw new ValidationException("Повторяющаяся ступень шкалы у " + c.id() + ": " + s.value());
            }
            max = Math.max(max, s.value());
        }
        if (max <= 0) {
            throw new ValidationException("Максимум шкалы " + c.id() + " должен быть больше 0");
        }
    }
}