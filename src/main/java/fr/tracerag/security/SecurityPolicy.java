package fr.tracerag.security;

import static fr.tracerag.rag.TextSupport.fold;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class SecurityPolicy {
    private static final List<Pattern> INJECTIONS = List.of(
            Pattern.compile("ignore (?:toutes? )?(?:les )?(?:instructions?|consignes?)"),
            Pattern.compile("(?:revele|affiche|donne).{0,30}(?:prompt systeme|system prompt|instructions? internes?)"),
            Pattern.compile("(?:contourne|desactive).{0,25}(?:securite|garde-fou|regles?)"),
            Pattern.compile("jailbreak"),
            Pattern.compile("(?:exfiltre|extrais).{0,30}(?:secrets?|credentials?|identifiants?)"),
            Pattern.compile("(?:donne|affiche|revele|liste).{0,25}(?:mot de passe|api key|cle api|token secret)"));

    private static final List<Pattern> SECRET_VALUES = List.of(
            Pattern.compile("\\b(?:sk|pk)_[a-zA-Z0-9_-]{16,}\\b"),
            Pattern.compile("\\bgh[pousr]_[a-zA-Z0-9]{20,}\\b"),
            Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"));

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)(?:\\+33[ .-]?|0)[1-9](?:[ .-]?\\d{2}){4}\\b");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]){11,30}\\b");

    public Decision assessQuestion(String question) {
        String normalized = fold(question);
        if (INJECTIONS.stream().anyMatch(pattern -> pattern.matcher(normalized).find())) {
            return Decision.block("Demande bloquée par la politique anti-injection/exfiltration.");
        }
        if (SECRET_VALUES.stream().anyMatch(pattern -> pattern.matcher(question).find())) {
            return Decision.block("Un secret potentiel a été détecté dans la question.");
        }
        return Decision.allow();
    }

    public Decision validateDocument(String content) {
        if (SECRET_VALUES.stream().anyMatch(pattern -> pattern.matcher(content).find())) {
            return Decision.block("Le document contient un secret potentiel et n'a pas été indexé.");
        }
        String normalized = fold(content);
        if (INJECTIONS.stream().anyMatch(pattern -> pattern.matcher(normalized).find())) {
            return Decision.block("Le document contient une instruction potentiellement malveillante.");
        }
        return Decision.allow();
    }

    public String redact(String value) {
        String redacted = EMAIL.matcher(value).replaceAll("[EMAIL_REDACTED]");
        redacted = PHONE.matcher(redacted).replaceAll("[PHONE_REDACTED]");
        redacted = IBAN.matcher(redacted).replaceAll("[IBAN_REDACTED]");
        for (Pattern pattern : SECRET_VALUES) {
            redacted = pattern.matcher(redacted).replaceAll("[SECRET_REDACTED]");
        }
        return redacted;
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision block(String reason) {
            return new Decision(false, reason);
        }
    }
}

