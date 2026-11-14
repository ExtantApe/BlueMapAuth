package com.extantape.bluemapauth.access;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A player's permissions while they're offline, through LuckPerms. Only loaded when LuckPerms is installed. */
final class OfflinePermissions {
    private OfflinePermissions() {}

    /** Their permissions, or null if LuckPerms can't load them in time. */
    static Predicate<String> of(UUID player) {
        try {
            User u = LuckPermsProvider.get().getUserManager().loadUser(player).get(3, TimeUnit.SECONDS);
            if (u == null) return null;
            var data = u.getCachedData().getPermissionData();
            return node -> data.checkPermission(node).asBoolean();
        } catch (Exception | LinkageError e) {
            return null;
        }
    }
}
