package com.cqafrdm.whatif;

import com.cqafrdm.checklist.Criterion;
import java.util.List;

/** criteria — несохранённый черновик из редактора; или checklistVersionId — сохранённая версия. groupId — опционально. */
public record WhatIfRequest(String groupId, Long checklistVersionId, List<Criterion> criteria) {}
