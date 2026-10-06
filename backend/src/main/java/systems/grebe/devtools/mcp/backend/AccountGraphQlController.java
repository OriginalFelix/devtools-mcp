package systems.grebe.devtools.mcp.backend;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import systems.grebe.devtools.mcp.api.LoginResult;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.api.PermissionInfo;
import systems.grebe.devtools.mcp.api.RoleInfo;
import systems.grebe.devtools.mcp.api.UserInfo;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.ApiToken;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.RoleService;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;

/**
 * Anmeldung der Desktop-Apps ({@code login}, {@code logout}, {@code changePassword}) und die Verwaltung von Benutzern
 * und Rollen (nur mit {@link Permission#USERS_MANAGE}).
 */
@Controller
public class AccountGraphQlController {

    /** Gültigkeit einer Anmeldung; die Desktop-App meldet sich beim Beenden ab. */
    static final Duration SESSION = Duration.ofDays(30);

    private final AccountService accounts;
    private final RoleService roles;
    private final TokenService tokens;

    public AccountGraphQlController(AccountService accounts, RoleService roles, TokenService tokens) {
        this.accounts = accounts;
        this.roles = roles;
        this.tokens = tokens;
    }

    // ---------------------------------------------------------------- Anmeldung

    /** Ohne Token aufrufbar. Fehlversuche bremst {@link systems.grebe.devtools.mcp.backend.account.LoginThrottle}. */
    @MutationMapping
    public LoginResult login(@Argument String username, @Argument String password, @Argument String client) {
        UserAccount u = accounts.authenticate(username, password);
        TokenService.IssuedToken issued = tokens.issueSession(u, client, SESSION);
        return new LoginResult(issued.jwt(), String.valueOf(issued.token().expiresAt()), u.passwordChangeRequired());
    }

    /** Beendet die Anmeldung dieses Tokens; persönliche Tokens bleiben gültig (Widerruf in „Mein Konto“). */
    @MutationMapping
    public boolean logout(@ContextValue(name = GraphQlAuth.TOKEN, required = false) TokenService.TokenUser token) {
        if (token == null) {
            return false;
        }
        if (token.kind() == ApiToken.Kind.SESSION) {
            tokens.end(token.tokenId());
        }
        return true;
    }

    @MutationMapping
    public boolean changePassword(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                  @Argument String currentPassword, @Argument String newPassword) {
        accounts.changePassword(GraphQlAuth.requireSignedIn(user).id(), currentPassword, newPassword);
        return true;
    }

    // ---------------------------------------------------------------- Verwaltung

    @QueryMapping
    public List<UserInfo> users(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        return accounts.users().stream().map(AccountGraphQlController::info).toList();
    }

    @QueryMapping
    public List<RoleInfo> roles(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        return roles.roles().stream().map(AccountGraphQlController::info).toList();
    }

    @QueryMapping
    public List<PermissionInfo> permissionCatalog(
            @ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        GraphQlAuth.require(user);
        return Arrays.stream(Permission.values()).map(PermissionInfo::of).toList();
    }

    @MutationMapping
    public UserInfo createUser(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String username, @Argument String displayName, @Argument String email,
                               @Argument List<String> roles, @Argument String password,
                               @Argument Boolean passwordChangeRequired) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        return info(accounts.create(username, displayName, email, roles, password,
                passwordChangeRequired == null || passwordChangeRequired));
    }

    @MutationMapping
    public UserInfo updateUser(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument long id, @Argument String displayName, @Argument String email,
                               @Argument List<String> roles, @Argument boolean enabled) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        return info(accounts.update(id, displayName, email, roles, enabled));
    }

    @MutationMapping
    public boolean setUserPassword(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                   @Argument long id, @Argument String password,
                                   @Argument Boolean passwordChangeRequired) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        accounts.resetPassword(id, password, passwordChangeRequired == null || passwordChangeRequired);
        return true;
    }

    @MutationMapping
    public boolean deleteUser(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument long id) {
        UserAccount u = GraphQlAuth.require(user, Permission.USERS_MANAGE);
        if (u.id() == id) {
            throw new IllegalArgumentException("Das eigene Konto kann nicht gelöscht werden.");
        }
        accounts.delete(id);
        return true;
    }

    @MutationMapping
    public RoleInfo createRole(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String name, @Argument String description,
                               @Argument List<String> permissions) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        return info(roles.create(name, description, permissions));
    }

    @MutationMapping
    public RoleInfo updateRole(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument long id, @Argument String name, @Argument String description,
                               @Argument List<String> permissions) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        return info(roles.update(id, name, description, permissions));
    }

    @MutationMapping
    public boolean deleteRole(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument long id) {
        GraphQlAuth.require(user, Permission.USERS_MANAGE);
        roles.delete(id);
        return true;
    }

    static UserInfo info(UserAccount u) {
        return new UserInfo(u.id(), u.username(), u.displayName(), u.email(), u.enabled(), u.passwordChangeRequired(),
                String.valueOf(u.createdAt()), u.roles());
    }

    static RoleInfo info(Role r) {
        return new RoleInfo(r.id(), r.name(), r.description(), r.builtin(), r.grants().list(), r.users());
    }
}
