package fi.helsinki.cs.tmc.comet.acl;

import fi.helsinki.cs.tmc.comet.Config;
import fi.helsinki.cs.tmc.comet.SessionAttributes;
import java.io.IOException;
import java.util.Map;
import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.DefaultSecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.monoid.web.FormContent;
import us.monoid.web.Resty;

/**
 * Checks that authentication information is present in a handshake and stores it in the session.
 */
public class TmcSecurityPolicy extends DefaultSecurityPolicy implements ServerSession.RemoveListener {
    private static final Logger log = LoggerFactory.getLogger(TmcSecurityPolicy.class);
    
    private Config config;
    
    public TmcSecurityPolicy(Config config) {
        this.config = config;
    }
    
    @Override
    public boolean canHandshake(BayeuxServer server, ServerSession session, ServerMessage message) {
        if (session.isLocalSession()) {
            return true;
        }
        
        Map<String, Object> ext = message.getExt();
        if (ext == null) {
            log.info("Handshake denied: message has no ext part");
            return false;
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> authData = (Map<String, Object>)ext.get("authentication");
        if (authData == null) {
            log.info("Handshake denied: message has no authentication record");
            return false;
        }
        
        String serverBaseUrl = getString(authData, "serverBaseUrl");
        String username = getString(authData, "username");
        String password = getString(authData, "password");
        String backendKey = getString(authData, "backendKey");
        
        if (serverBaseUrl != null && username != null && password != null) {
            try {
                if (authenticateFrontend(serverBaseUrl, username, password)) {
                    session.setAttribute(SessionAttributes.SERVER_BASE_URL, serverBaseUrl);
                    session.setAttribute(SessionAttributes.USERNAME, username);
                    return true;
                } else {
                    log.info("Handshake denied: invalid username, password or serverBaseUrl");
                    return false;
                }
            } catch (IOException e) {
                log.error("Failed to authenticate at " + serverBaseUrl + ": " + e.toString());
                return false;
            }
        } else if (backendKey != null && serverBaseUrl != null) {
            if (authenticateBackend(backendKey)) {
                session.setAttribute(SessionAttributes.SERVER_BASE_URL, serverBaseUrl);
                session.setAttribute(SessionAttributes.IS_BACKEND, true);
                return true;
            } else {
                log.info("Handshake denied: invalid backend key or serverBaseUrl");
                return false;
            }
        } else {
            log.info("Handshake denied: authentication record missing some keys");
            return false;
        }
    }
    
    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof String) {
            return (String)value;
        } else {
            return null;
        }
    }
    
    private boolean authenticateFrontend(String serverBaseUrl, String username, String password) throws IOException {
        if (!config.isAllowedServer(serverBaseUrl)) {
            log.info("Attempt to use unknown authentication server: " + serverBaseUrl);
            return false;
        }
        
        String authUrl = config.normalizeServerUrl(serverBaseUrl) + "/auth.text";
        FormContent data = Resty.form("username=" + Resty.enc(username) + "&password=" + Resty.enc(password));
        String response = new Resty().text(authUrl, data).toString().trim();
        return response.equals("OK");
    }
    
    private boolean authenticateBackend(String key) {
        return config.isBackendKey(key);
    }

    public void removed(ServerSession session, boolean timeout) {
        session.removeAttribute(SessionAttributes.SERVER_BASE_URL);
        session.removeAttribute(SessionAttributes.USERNAME);
    }
}