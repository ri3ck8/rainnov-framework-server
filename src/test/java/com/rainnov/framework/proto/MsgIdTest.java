package com.rainnov.framework.proto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * generateMsgId 任务单元测试：
 * 验证 MsgId.java 由 Gradle 任务正确生成，消息号常量值正确，模块归类正确。
 */
class MsgIdTest {

    @Test
    @DisplayName("MsgId.LOGIN.LOGIN_REQ == 1001")
    void loginReq_equals1001() {
        assertEquals(1001, MsgId.LOGIN.LOGIN_REQ);
    }

    @Test
    @DisplayName("MsgId.LOGIN.LOGIN_RESP == 1002")
    void loginResp_equals1002() {
        assertEquals(1002, MsgId.LOGIN.LOGIN_RESP);
    }

    @Test
    @DisplayName("MsgId.LOGIN.LOGOUT_REQ == 1003")
    void logoutReq_equals1003() {
        assertEquals(1003, MsgId.LOGIN.LOGOUT_REQ);
    }

    @Test
    @DisplayName("MsgId.LOGIN.LOGOUT_REQ == 1004")
    void logoutResp_equals1004() {
        assertEquals(1004, MsgId.LOGIN.LOGOUT_RESP);
    }

    @Test
    @DisplayName("LOGIN module class exists and contains expected constants")
    void loginModuleClassExists() {
        assertNotNull(MsgId.LOGIN.class);
        assertEquals(1001, MsgId.LOGIN.LOGIN_REQ);
        assertEquals(1002, MsgId.LOGIN.LOGIN_RESP);
        assertEquals(1003, MsgId.LOGIN.LOGOUT_REQ);
        assertEquals(1004, MsgId.LOGIN.LOGOUT_RESP);
    }

    @Test
    @DisplayName("CamelCase message names are converted to UPPER_SNAKE_CASE constants")
    void camelCaseToUpperSnakeCase() throws Exception {
        assertNotNull(MsgId.LOGIN.class.getField("LOGIN_REQ"));
        assertNotNull(MsgId.LOGIN.class.getField("LOGIN_RESP"));
        assertNotNull(MsgId.LOGIN.class.getField("LOGOUT_REQ"));
        assertNotNull(MsgId.LOGIN.class.getField("LOGOUT_RESP"));
    }

    @Test
    @DisplayName("MsgId class has private constructor (not instantiable)")
    void msgIdNotInstantiable() {
        var constructors = MsgId.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertFalse(constructors[0].canAccess(null));
    }
}
