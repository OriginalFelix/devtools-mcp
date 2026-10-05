package systems.grebe.devtools.mcp.web;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.backend.catalog.ModuleCatalog;

/** Startseite: wie sich eine Desktop-App mit dem Server verbindet. */
@Route("")
@PageTitle("Übersicht – DevTools MCP")
@PermitAll
public class OverviewView extends VerticalLayout {

    public OverviewView(ModuleCatalog catalog) {
        add(new H2("Team-Server"));

        TextField url = new TextField("Server-Adresse für die Desktop-App");
        url.setValue(Ui.serverUrl());
        url.setReadOnly(true);
        url.setWidth("32rem");
        add(url);

        add(new Paragraph("Der MCP-Server läuft in deiner Desktop-App: dort beim Start bzw. im Tab „Backend“ diese "
                + "Adresse eintragen und mit Benutzername und Passwort anmelden. Was du darfst, bestimmen deine Rollen; "
                + "Anmeldungen und Tokens für den Start ohne Anmeldedialog unter "),
                new RouterLink("Mein Konto", AccountView.class));
        add(new Paragraph("Hier verwaltest du Profile, Einstellungen, Projekte und Skills – die Desktop-App holt sie "
                + "sich regelmäßig. " + catalog.modules().size() + " Module bekannt."));
    }
}
