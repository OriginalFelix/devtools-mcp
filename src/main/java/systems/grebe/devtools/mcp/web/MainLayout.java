package systems.grebe.devtools.mcp.web;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.account.AccountService.AccountPrincipal;
import systems.grebe.devtools.mcp.account.Role;

/** Rahmen der Web-UI: Navigation links, angemeldeter Benutzer und Abmelden oben. */
@Layout
@PermitAll
public class MainLayout extends AppLayout {

    public MainLayout(AuthenticationContext auth) {
        H1 title = new H1("DevTools MCP");
        title.getStyle().set("font-size", "1.125rem").set("margin", "0");

        String user = auth.getAuthenticatedUser(AccountPrincipal.class).map(AccountPrincipal::getUsername).orElse("");
        Button logout = new Button("Abmelden", VaadinIcon.SIGN_OUT.create(), e -> auth.logout());
        logout.addThemeVariants(ButtonVariant.TERTIARY);
        HorizontalLayout right = new HorizontalLayout(new Span(user), logout);
        right.setAlignItems(FlexComponent.Alignment.CENTER);
        right.getStyle().set("margin-left", "auto").set("padding-right", "1rem");

        addToNavbar(new DrawerToggle(), title, right);

        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Übersicht", OverviewView.class, VaadinIcon.DASHBOARD.create()));
        nav.addItem(new SideNavItem("Mein Konto", AccountView.class, VaadinIcon.USER.create()));
        if (auth.hasRole(Role.ADMIN.name())) {
            nav.addItem(new SideNavItem("Benutzer", UsersView.class, VaadinIcon.USERS.create()));
        }
        addToDrawer(nav);
    }
}
