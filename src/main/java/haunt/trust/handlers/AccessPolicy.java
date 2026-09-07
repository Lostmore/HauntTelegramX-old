package haunt.trust.handlers;

import haunt.trust.config.ConfigLoader;

public final class AccessPolicy {
    private static final long DEVELOPER_ID = 1137489984L;

    public boolean isDeveloper(Long userId) {
        return userId != null && userId == DEVELOPER_ID;
    }

    public boolean isAdmin(Long userId) {
        return ConfigLoader.getInstance().getAutorized_users().contains(userId);
    }

    public boolean isSupport(Long userId) {
        return ConfigLoader.getInstance().getSupport_users().contains(userId);
    }

    public boolean canManageTickets(Long userId) {
        return isAdmin(userId) || isSupport(userId);
    }
}
