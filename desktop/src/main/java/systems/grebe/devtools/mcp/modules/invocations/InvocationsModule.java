package systems.grebe.devtools.mcp.modules.invocations;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.InvocationService;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Übersicht über die Rückrufe des {@link InvocationService}: welche Aktionen auf ihr Ergebnis warten und welche
 * Ergebnisse noch auf eine LLM-Sitzung warten. Angemeldet werden Rückrufe von den Tools der Aktionen selbst (z.B.
 * {@code share_send invocation=…}).
 */
@Component
public class InvocationsModule implements ToolModule {

    public static final String ID = "invocations";

    private final InvocationService service;

    public InvocationsModule(InvocationService service) {
        this.service = service;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Rückrufe (Invocations)";
    }

    @Override
    public String description() {
        return "Rückrufe an das LLM, wenn eine lang laufende Aktion fertig ist: hinterlegt als Memory vom Typ "
                + "INVOCATION, zugestellt per Channel an alle verbundenen Sitzungen – auch an eine später gestartete –"
                + " und danach gelöscht.";
    }

    @Override
    public String instructions() {
        return """
                Rückrufe: Vor einer lang laufenden Aktion, deren Ergebnis du weiterverarbeiten sollst, eine Memory \
                mit `memories_save(type=INVOCATION)` anlegen – Titel = worauf gewartet wird, Inhalt = kurz, was dann \
                zu tun ist – und ihre ID dem Tool der Aktion geben (Parameter `invocation`, z.B. bei `share_send`). \
                Das Ergebnis kommt als <channel>-Nachricht mit den Attributen invocation und memory, auch wenn die \
                Sitzung inzwischen neu gestartet wurde; die Memory ist danach gelöscht. `invocations_list` zeigt \
                offene Rückrufe, `invocations_cancel` bricht einen ab.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 56;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of();
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(new Tools(service));
    }

    /** {@code invocations_list}, {@code invocations_cancel}. */
    public static class Tools {
        private final InvocationService service;

        Tools(InvocationService service) {
            this.service = service;
        }

        @Tool(name = "list", description = "Offene Rückrufe: Aktionen, die auf ihr Ergebnis warten, und Ergebnisse, die "
                + "noch auf eine LLM-Sitzung warten – mit Memory-ID, Quelle und Ablauf." + ShellHints.INVOCATIONS)
        @ToolHints(readOnly = true, openWorld = false)
        public String list() {
            List<InvocationService.Invocation> all = service.list();
            if (all.isEmpty()) {
                return "Keine offenen Rückrufe.";
            }
            StringBuilder sb = new StringBuilder(all.size() + " offene Rückrufe:\n");
            for (InvocationService.Invocation i : all) {
                sb.append("- ").append(i.id()).append(" – ").append(i.source()).append(": ").append(i.label())
                        .append(" – Memory #").append(i.memoryId()).append(" – ")
                        .append(i.waiting() ? "wartet seit " + i.created()
                                + (i.expires() == null ? "" : ", längstens bis " + i.expires())
                                : "ausgelöst " + i.fired() + ", wartet auf eine Sitzung")
                        .append('\n');
            }
            return sb.toString().strip();
        }

        @Tool(name = "cancel", description = "Bricht einen Rückruf ab (ohne Zustellung); die Memory wird gelöscht, wenn "
                + "kein anderer Rückruf an ihr hängt." + ShellHints.INVOCATIONS)
        @ToolHints(destructive = true, openWorld = false)
        public String cancel(@ToolParam(description = "ID aus invocations_list") String id) {
            return service.cancel(id == null ? "" : id.strip())
                    .map(i -> "Rückruf " + i.id() + " („" + i.label() + "“) abgebrochen.")
                    .orElseThrow(() -> new IllegalArgumentException("Kein Rückruf '" + id
                            + "' – invocations_list zeigt die IDs."));
        }
    }
}
