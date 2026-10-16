package com.killhuishenghuo.core.net

/**
 * The real 惠生活798 gateway contract.
 *
 * Every value in this file was read out of working open-source clients of the
 * same product (see README "取证来源"), and the host was independently probed
 * live with `curl`-equivalent requests before being written down. Anything that
 * is still a guess is marked `TODO` and listed in the README's 待补充清单.
 *
 * The official app additionally carries `X-Garen` / `X-Nami` / `X-Ashe` headers
 * for its own RPC layer; that layer is private and unused here. See
 * [com.killhuishenghuo.core.sign.ApiSigner] for that scheme and
 * [Authenticator] for the signature this REST gateway actually checks.
 */
object ApiConstants {

    // ------------------------------------------------------------ transport

    /**
     * Verified live: `GET {base}captcha/?s=…` answers `200 image/jpeg` and
     * `GET {base}ui/app/master` answers `{"code":0,"data":{"ads":[]},…}`.
     */
    const val DEFAULT_BASE_URL = "https://i.ilife798.com/api/v1/"

    /** Version the gateway is told about; `3.1.7` was in use in the captured traffic. */
    const val APP_VERSION_NAME = "3.1.9"

    /** `VersionCode` request header. Same encoding the official app uses: `3.1.7` -> `31007`. */
    const val APP_VERSION_CODE = "31009"

    /** `user-agent` request header, verbatim from the captured clients. */
    const val USER_AGENT = "Android_ilife798_$APP_VERSION_NAME"

    // -------------------------------------------------------------- headers

    /** Session token. Not `Bearer`-prefixed: the raw token is sent. */
    const val HEADER_AUTHORIZATION = "Authorization"

    /**
     * Client capability set. **This one is not a constant.**
     *
     * A working client of the same product documents that the gateway keeps two
     * token scopes and that they are not interchangeable: the device-control
     * scope (`1,1`) is what `dev/start` and `dev/end` accept, and an
     * account-service token (`1,5`) is refused there with `code == -21`
     * ("需要设备控制登录信息"). Sending `1,1` on everything works only as long
     * as the token really is device-scoped, and sending `1,5` on a device call
     * is the documented way to get a refusal, so the header is resolved per
     * request from the path — see [applicationTypeFor].
     */
    const val HEADER_APPLICATION_TYPE = "ApplicationType"

    /** Device control: `ui/app/master`, `dev/start`, `dev/end`, device status. */
    const val APPLICATION_TYPE_DEVICE = "1,1"

    /**
     * Device login: requests a device-control scoped token.
     *
     * `acc/login` accepts either scope and issues a token for the one it is
     * asked with, so this has to be `1,1` — an account-scoped token cannot open
     * a valve. The reference client implements exactly this as a per-login
     * toggle (`asApp`), and its device login is the `1,1` branch.
     */
    const val APPLICATION_TYPE_LOGIN = APPLICATION_TYPE_DEVICE

    /**
     * Account services: balance, wallet, score and mission endpoints.
     *
     * Nothing in this client calls one, since the app only opens water. The
     * constant exists so that adding such a call is a deliberate choice of scope
     * rather than an accident of the default.
     */
    const val APPLICATION_TYPE_ACCOUNT = "1,5"

    /**
     * The scope to advertise for [path].
     *
     * The default is *device*, not account, because device control is what this
     * app is for and the failure modes are asymmetric: asking for the wrong
     * scope on a device call gets `code == -21` and no water, while this client
     * issues no account-service calls at all.
     */
    fun applicationTypeFor(path: String): String = when (path) {
        PATH_CAPTCHA, PATH_SMS_CODE, PATH_LOGIN -> APPLICATION_TYPE_LOGIN
        PATH_MASTER,
        PATH_DEVICE_STATUS,
        PATH_DEVICE_DETAIL,
        PATH_DEVICE_START,
        PATH_DEVICE_END,
        PATH_DEVICE_FAVO,
        PATH_QR_USE,
        -> APPLICATION_TYPE_DEVICE

        else -> APPLICATION_TYPE_DEVICE
    }

    const val HEADER_VERSION_CODE = "VersionCode"

    // -------------------------------------------------------------- paths

    /** `GET` -> JPEG bytes. Query: `s` = 10-char key, `r` = epoch millis. */
    const val PATH_CAPTCHA = "captcha/"

    /** `POST` JSON -> envelope. Body: `{un, authCode, s}`. */
    const val PATH_SMS_CODE = "acc/login/code"

    /** `POST` JSON -> envelope with `data.al.token` and `data.al.uid`. */
    const val PATH_LOGIN = "acc/login"

    /** `GET` -> account + `data.favos[]`, the bound hot-water devices. */
    const val PATH_MASTER = "ui/app/master"

    /** `GET` -> live device state. Query: `did`, `more`. */
    const val PATH_DEVICE_STATUS = "ui/app/dev/status"

    /** `GET` -> device detail. Query: `did`, `apply=6`. */
    const val PATH_DEVICE_DETAIL = "ui/app/dev/home/1"

    /**
     * `GET` -> opens the valve. Query: `did`, `upgrade=true`, `ptype`, `args`,
     * `rcp=false`, `cnt=1`.
     */
    const val PATH_DEVICE_START = "dev/start"

    /** `GET` -> closes the valve. Query: `did`, `rcp=false`. */
    const val PATH_DEVICE_END = "dev/end"

    /** `GET` -> device can be resolved from a scanned QR payload. Query: `id`. */
    const val PATH_QR_USE = "qr/use"

    /**
     * `GET` -> adds or removes this account's favourite device. Query: `did`, `remove`.
     *
     * This is the "add device" call: `remove=0` puts a device into the `favos[]`
     * array that [PATH_MASTER] answers with, which is the only list this app can
     * see. Read out of a working client of the same product, where it is named
     * plainly: 添加或移除账户收藏设备.
     */
    const val PATH_DEVICE_FAVO = "dev/favo"

    // ---------------------------------------------------------- parameters

    const val PARAM_DID = "did"
    const val PARAM_MORE = "more"
    const val PARAM_UPGRADE = "upgrade"
    const val PARAM_PTYPE = "ptype"
    const val PARAM_ARGS = "args"
    const val PARAM_RCP = "rcp"
    const val PARAM_CNT = "cnt"

    /** `qr/use` takes the id the scan produced. */
    const val PARAM_ID = "id"

    /** `dev/favo` takes `remove`: see [FAVO_ADD] / [FAVO_REMOVE]. */
    const val PARAM_REMOVE = "remove"

    /** `dev/favo&remove=0` — add the device to the account. */
    const val FAVO_ADD = "0"

    /** `dev/favo&remove=1` — take it off the account. Unused today; kept so the pair is legible. */
    const val FAVO_REMOVE = "1"

    /**
     * `ptype` is the funding source. `91` = the dormitory wallet balance, which
     * is the only one that matters for a 798 dormitory account.
     */
    const val PTYPE_WALLET = 91

    /** `ptype` for Alipay, kept for completeness. */
    const val PTYPE_ALIPAY = 21

    const val ARGS_EMPTY = ""

    /**
     * Offset at which `silk_id` is spliced into the 16-character random body of
     * a logged-in `X-Nami`.
     *
     * Belongs to the private RPC scheme, which this client does not send; kept
     * because [com.killhuishenghuo.core.sign.DeviceIdentity] is a working
     * reference implementation of the scheme the user captured. The official
     * app's real offset is unconfirmed.
     */
    const val SILK_ID_INSERT_INDEX = 8

    // --------------------------------------------------------- business codes

    /** `code == 0` is the only success value. */
    const val CODE_SUCCESS = 0

    /** Every authenticated call answers this once the session is dead. */
    const val CODE_SESSION_EXPIRED = -99

    /**
     * Server-side rate limit. The captured client retries once after a short
     * pause instead of surfacing it.
     */
    const val CODE_RATE_LIMITED = -98

    // ------------------------------------------- dev/start refusal codes
    //
    // These were read out of a working client of the same product, which maps
    // each one to a sentence. They are worth carrying because they are the
    // whole answer to "why did nothing happen": the gateway refuses to open a
    // valve for reasons that are all about the account, and a bare
    // "服务器拒绝了（错误码 -52）" tells the user nothing they can act on.
    //
    // They apply to the device-control scope. A refusal with a code that is not
    // in this list still falls through to the generic server error.

    /** Balance exhausted or single-use price unpaid. */
    const val CODE_PAYMENT_REQUIRED = -82

    /** The account is in arrears. */
    const val CODE_ARREARS = -52

    /** No auto-debit agreement on file. */
    const val CODE_NO_AGREEMENT = -88

    /** The auto-debit agreement lapsed. */
    const val CODE_AGREEMENT_EXPIRED = -87

    /** No campus card bound to the account. */
    const val CODE_NO_CARD = -20

    /** The device is still getting ready; retrying shortly usually works. */
    const val CODE_DEVICE_NOT_READY = -19

    /** The token's scope is wrong for device control. */
    const val CODE_WRONG_SCOPE = -21

    /**
     * Someone else's session is on this device — the server said "当前设备正在使用".
     *
     * Observed on a real device, and **not the same situation as
     * [com.killhuishenghuo.data.model.Device.STATUS_UNREPORTED]**: there the server
     * also declines to start, but it does not claim anyone is using it. Here it
     * does, and no amount of retrying changes that. The cause is on the device or
     * on another account, so the honest answer is to say so rather than to keep
     * the button offering a start that will be refused.
     */
    const val CODE_DEVICE_IN_USE = -15

    /**
     * The session belongs to this device and has to be settled on it.
     *
     * The server's own words: "设备运行中,请在设备上点击结算". Same family as
     * [CODE_DEVICE_IN_USE] — the fix is at the machine, not in the app.
     */
    const val CODE_SETTLE_ON_DEVICE = -2
}
