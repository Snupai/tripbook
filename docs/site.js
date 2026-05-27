(function () {
    var storageKey = "tripbook-language";

    function storedLanguage() {
        try {
            return window.localStorage.getItem(storageKey);
        } catch (error) {
            return null;
        }
    }

    function preferredLanguage() {
        var stored = storedLanguage();
        if (stored === "en" || stored === "de") {
            return stored;
        }
        var browserLanguage = (window.navigator.language || "").toLowerCase();
        return browserLanguage.indexOf("de") === 0 ? "de" : "en";
    }

    function persistLanguage(language) {
        try {
            window.localStorage.setItem(storageKey, language);
        } catch (error) {
            return;
        }
    }

    function setMetadata(language) {
        var title = document.querySelector("title");
        if (title && title.dataset.titleEn && title.dataset.titleDe) {
            title.textContent = language === "de" ? title.dataset.titleDe : title.dataset.titleEn;
        }

        var description = document.querySelector('meta[name="description"]');
        if (description && description.dataset.descriptionEn && description.dataset.descriptionDe) {
            description.setAttribute(
                    "content",
                    language === "de" ? description.dataset.descriptionDe : description.dataset.descriptionEn);
        }
    }

    function applyLanguage(language) {
        document.documentElement.lang = language;
        document.querySelectorAll("[data-lang]").forEach(function (element) {
            element.hidden = element.dataset.lang !== language;
        });
        document.querySelectorAll("[data-language-toggle]").forEach(function (button) {
            button.textContent = language === "de" ? "English" : "Deutsch";
            button.setAttribute(
                    "aria-label",
                    language === "de" ? "Show English version" : "Deutsche Version anzeigen");
        });
        setMetadata(language);
        persistLanguage(language);
    }

    document.addEventListener("DOMContentLoaded", function () {
        var language = preferredLanguage();
        applyLanguage(language);

        document.querySelectorAll("[data-language-toggle]").forEach(function (button) {
            button.addEventListener("click", function () {
                applyLanguage(document.documentElement.lang === "de" ? "en" : "de");
            });
        });
    });
})();
