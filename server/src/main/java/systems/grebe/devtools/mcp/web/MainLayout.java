package systems.grebe.devtools.mcp.web;

import java.util.List;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.router.AfterNavigationObserver;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.profile.Profile;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;

/** Rahmen der Web-UI: Navigation links, angemeldeter Benutzer und Abmelden oben. */
@Layout
@PermitAll
public class MainLayout extends AppLayout implements AfterNavigationObserver {

    private final ProfileService profiles;
    private final long userId;
    private final Select<Profile> profileSelect = new Select<>();

    public MainLayout(AuthenticationContext auth, ProfileService profiles) {
        this.profiles = profiles;
        H1 title = new H1("DevTools MCP");
        title.getStyle().set("font-size", "1.125rem").set("margin", "0");

        AccountPrincipal principal = auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow();
        this.userId = principal.id();
        Button logout = new Button("Abmelden", VaadinIcon.SIGN_OUT.create(), e -> auth.logout());
        logout.addThemeVariants(ButtonVariant.TERTIARY);

        // Aktives Profil umschalten – die Desktop-Apps des Benutzers übernehmen es beim nächsten Abgleich
        profileSelect.setItemLabelGenerator(Profile::name);
        profileSelect.setTooltipText("Aktives Profil deiner Desktop-Apps");
        profileSelect.setWidth("12rem");
        profileSelect.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null) {
                Ui.run(() -> profiles.activate(userId, e.getValue().id()),
                        "Profil „" + e.getValue().name() + "“ aktiv");
            }
        });

        HorizontalLayout right = new HorizontalLayout(profileSelect, new Span(principal.getUsername()), logout);
        right.setAlignItems(FlexComponent.Alignment.CENTER);
        right.getStyle().set("margin-left", "auto").set("padding-right", "1rem");

        addToNavbar(new DrawerToggle(), title, right);

        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Übersicht", OverviewView.class, VaadinIcon.DASHBOARD.create()));
        nav.addItem(new SideNavItem("Projekte", ProjectsView.class, VaadinIcon.FOLDER_OPEN.create()));
        nav.addItem(new SideNavItem("Einstellungen", SettingsView.class, VaadinIcon.COG.create()));
        nav.addItem(new SideNavItem("Skripte", ScriptsView.class, VaadinIcon.CODE.create()));
        nav.addItem(new SideNavItem("Profile", ProfilesView.class, VaadinIcon.RECORDS.create()));
        nav.addItem(new SideNavItem("Mein Konto", AccountView.class, VaadinIcon.USER.create()));
        if (auth.hasRole(Role.ADMIN.name())) {
            nav.addItem(new SideNavItem("Globale Einstellungen", GlobalSettingsView.class,
                    VaadinIcon.GLOBE.create()));
            nav.addItem(new SideNavItem("Benutzer", UsersView.class, VaadinIcon.USERS.create()));
        }
        addToDrawer(nav);
    }

    /** Profilliste nach jeder Navigation neu laden (Profile können auf der Profil-Seite angelegt worden sein). */
    @Override
    public void afterNavigation(AfterNavigationEvent event) {
        refreshProfiles();
    }

    void refreshProfiles() {
        List<Profile> list = profiles.profiles(userId);
        long active = profiles.activeProfile(userId).id();
        profileSelect.setItems(list);
        list.stream().filter(p -> p.id() == active).findFirst().ifPresent(profileSelect::setValue);
    }
}
