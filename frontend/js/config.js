window.CQA = window.CQA || {};
CQA.config = {
  API_BASE: "http://localhost:8080",
  // Демонстрационный режим работает сразу без backend. Для Spring Boot поставьте false.
  // ?mock=1 в адресе включает демо-режим без backend.
  USE_MOCK: new URLSearchParams(location.search).get("mock") === "1",
  BRAND: "Freedom"
};
