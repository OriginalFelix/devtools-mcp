package systems.grebe.devtools.mcp.web;

import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/** Anmeldung an der Web-UI (Formular-Login von Spring Security, Konten aus der Core-Datenbank). */
@Route(value = "login", autoLayout = false)
@PageTitle("Anmelden – DevTools MCP")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm form = new LoginForm();

    public LoginView() {
        setSizeFull();
        setAlignItems(FlexComponent.Alignment.CENTER);
        setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        LoginI18n i18n = LoginI18n.createDefault();
        i18n.getForm().setTitle("DevTools MCP");
        i18n.getForm().setUsername("Benutzername");
        i18n.getForm().setPassword("Passwort");
        i18n.getForm().setSubmit("Anmelden");
        i18n.getErrorMessage().setTitle("Anmeldung fehlgeschlagen");
        i18n.getErrorMessage().setMessage("Benutzername oder Passwort stimmt nicht, oder das Konto ist gesperrt.");
        form.setI18n(i18n);
        form.setAction("login");
        form.setForgotPasswordButtonVisible(false);
        add(form);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        form.setError(event.getLocation().getQueryParameters().getParameters().containsKey("error"));
    }
}
