package com.cleanhome.tv;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the launcher see other apps' media sessions
 * (MediaSessionManager.getActiveSessions needs an enabled notification
 * listener). It ignores notifications. install.sh enables it over ADB.
 */
public class MediaListener extends NotificationListenerService {
}
