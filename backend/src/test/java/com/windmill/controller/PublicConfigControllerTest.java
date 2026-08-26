package com.windmill.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PublicConfigControllerTest {

    @Test
    void omitsFirebaseWhenAnyWebFieldIsBlank() {
        PublicConfigController controller = new PublicConfigController(
                "kakao-key", "api", "", "proj", "sender", "app", "vapid");
        var config = controller.get();
        assertEquals("kakao-key", config.getKakaoJsKey());
        assertNull(config.getFirebaseApiKey());
        assertNull(config.getFirebaseVapidKey());
    }

    @Test
    void includesFirebaseWhenWebConfigIsComplete() {
        PublicConfigController controller = new PublicConfigController(
                "", "api", "proj.firebaseapp.com", "proj", "sender", "app", "vapid");
        var config = controller.get();
        assertNull(config.getKakaoJsKey());
        assertEquals("api", config.getFirebaseApiKey());
        assertEquals("proj.firebaseapp.com", config.getFirebaseAuthDomain());
        assertEquals("proj", config.getFirebaseProjectId());
        assertEquals("sender", config.getFirebaseMessagingSenderId());
        assertEquals("app", config.getFirebaseAppId());
        assertEquals("vapid", config.getFirebaseVapidKey());
    }
}
