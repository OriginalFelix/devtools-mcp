package systems.grebe.devtools.mcp.web;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.PermitAll;
import org.springframework.beans.factory.annotation.Value;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.server.McpAccess;

/** Startseite: MCP-Adresse und wie ein Client sich verbindet. */
@Route("")
@PageTitle("Übersicht – DevTools MCP")
@PermitAll
public class OverviewView extends VerticalLayout {

    public OverviewView(ToolRegistry registry, McpAccess access,
                        @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint) {
        add(new H2("MCP-Server"));

        TextField url = new TextField("Adresse");
        url.setValue(Ui.mcpUrl(endpoint));
        url.setReadOnly(true);
        url.setWidth("32rem");
        add(url);

        add(new Paragraph("Clients melden sich mit einem persönlichen Token an (Header „Authorization: Bearer …“). "
                + "Tokens erzeugst du unter "), new RouterLink("Mein Konto", AccountView.class));
        if (access.anonymousLocal()) {
            add(new Paragraph("Dieser Server lauscht nur lokal: Clients auf demselben Rechner dürfen sich auch ohne "
                    + "Token verbinden (Einzelplatz-Betrieb, Einstellungen der Desktop-App)."));
        }
        add(new Paragraph(registry.activeToolCount() + " Tools aktiv in " + registry.modules().size() + " Modulen."));
    }
}
