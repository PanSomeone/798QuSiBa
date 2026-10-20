package com.killhuishenghuo.core

/**
 * The project's public addresses.
 *
 * Here rather than inline at each use because four places need to agree on
 * them: the "about" section links to the repository, the feedback section links
 * to its issue tracker and to the user group, and all of them have to keep
 * pointing at the same project. A URL typed twice is a URL that drifts once.
 *
 * The owner is not configurable and deliberately not derived from anything in
 * the build: if this app is forked, the person forking should change this line
 * rather than discover that their fork still reports bugs to the original.
 */
object ProjectLinks {

    /** The repository. Also what the "star this project" row opens. */
    const val REPOSITORY = "https://github.com/PanSomeone/798QuSiBa"

    /**
     * Where a report goes.
     *
     * `/issues/new` rather than `/issues`: opening the list asks the reader to
     * find the button first, and half of them will instead read three closed
     * issues and give up. This lands on the form.
     */
    const val NEW_ISSUE = "$REPOSITORY/issues/new"

    /**
     * What the app tells the user to paste into that form.
     *
     * There is no server behind this project and no way to send anything from
     * the app itself, so the honest instruction is "put it on the clipboard,
     * then paste it where reports go".
     */
    const val FEEDBACK_HINT = "报告已复制，粘贴到 GitHub issue 里即可"

    /**
     * The user group, for the people who will never file a GitHub issue.
     *
     * A number rather than a link: QQ has no public page for a group, and the
     * `qm.qq.com` invite links carry a per-group key that cannot be derived from
     * the number — a hand-built one would be a dead link. So the number goes on
     * the clipboard (that always works), and QQ's own group card is opened only
     * if QQ is installed. See `openDeepLink` in `SettingsScreen`.
     */
    const val QQ_GROUP = "1126108304"

    /** What the row says once the number is on the clipboard. */
    const val QQ_GROUP_COPIED = "群号已复制，在 QQ 里搜一下就能进"

    /**
     * QQ's group-card deep link.
     *
     * Undocumented and unsupported by Tencent, which is exactly why the copy
     * happens first and this is allowed to fail silently: on a phone without QQ
     * the row still leaves the user with a number they can search for.
     */
    const val QQ_GROUP_SCHEME =
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP&card_type=group"
}
