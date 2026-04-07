package com.rainnov.framework.example;

import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.net.dispatch.MsgController;
import com.rainnov.framework.net.dispatch.MsgMapping;
import com.rainnov.framework.proto.LoginProto.C1001_LoginReq;
import com.rainnov.framework.proto.LoginProto.C1002_LoginResp;
import com.rainnov.framework.proto.LoginProto.C1003_LogoutReq;
import com.rainnov.framework.proto.LoginProto.C1004_LogoutResp;
import com.rainnov.framework.proto.MsgId;

/**
 * 登录模块示例 Controller。
 * 演示 @MsgController + @MsgMapping 的基本用法。
 */
@MsgController
public class LoginController {

    /**
     * 登录接口：requireAuth=false，无需鉴权即可访问。
     * 示例逻辑：根据 token 哈希生成 userId 并绑定到 Session。
     */
    @MsgMapping(value = MsgId.LOGIN.LOGIN_REQ, requireAuth = false)
    public C1002_LoginResp login(GameSession session, C1001_LoginReq req) {
        long userId = req.getToken().hashCode() & 0xFFFFFFFFL;
        session.bindUser(userId);
        return C1002_LoginResp.newBuilder().setUserId(userId).build();
    }

    /**
     * 登出接口：requireAuth=true（默认值），需要已登录才能访问。
     */
    @MsgMapping(MsgId.LOGIN.LOGOUT_REQ)
    public C1004_LogoutResp logout(GameSession session, C1003_LogoutReq req) {
        session.close();
        return C1004_LogoutResp.newBuilder().build();
    }
}
