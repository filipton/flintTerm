package dev.flint.term.ui

import dev.flint.term.R

/**
 * What the command palette searches over, and how it decides what comes first.
 *
 * Deliberately plain Kotlin: no Compose, no Android, nothing that needs a
 * device to run. The ranking is the part with the judgment in it, so it is the
 * part that has to be testable.
 */
enum class PaletteKind(val label: String) {
    HOST("Hosts"),
    SNIPPET("Snippets"),
    SETTING("Settings"),
    ACTION("Actions"),
}

/**
 * One thing the palette can find.
 *
 * [route] is where picking it goes; [id] names the host or snippet it stands
 * for, since those are looked up in the store rather than navigated to.
 */
data class PaletteEntry(
    val title: String,
    val subtitle: String = "",
    val kind: PaletteKind = PaletteKind.ACTION,
    val route: String = "",
    val id: String = "",
    /**
     * The text this entry shows, for the ones that come from the catalog rather
     * than from a host or a snippet.
     *
     * Held as resource ids and resolved by whoever draws the list, so a search
     * matches what the screen actually says in the language it is set to, and
     * the row it leads to can be found by the same name.
     */
    val titleRes: Int = 0,
    val subtitleRes: Int = 0,
) {
    /** The same entry with its text filled in. */
    fun resolved(res: (Int) -> String): PaletteEntry =
        if (titleRes == 0) this
        else copy(title = res(titleRes), subtitle = if (subtitleRes == 0) subtitle else res(subtitleRes))
}

/**
 * An entry the query found, with the characters that found it.
 *
 * [titleHits] and [subtitleHits] are offsets into the entry's own strings, so
 * the row can draw exactly those characters bold — which is the only honest
 * way to say why a result is in the list at all.
 */
data class PaletteMatch(
    val entry: PaletteEntry,
    val score: Int,
    val titleHits: List<Int> = emptyList(),
    val subtitleHits: List<Int> = emptyList(),
)

object PaletteSearch {

    /** How many hosts an empty field offers. Enough to be useful, few enough to read. */
    const val RECENT = 6

    // Three tiers, a thousand apart, so nothing in a weaker tier can ever climb
    // over a stronger one however well it scores inside its own.
    private const val TITLE_PREFIX = 3000
    private const val TITLE_SUBSEQUENCE = 2000
    private const val DESCRIPTION = 1000
    private const val TIER = 999

    /**
     * [entries] ranked against [query], best first.
     *
     * An empty query is not "everything": a list of two hundred settings is
     * nothing anyone reads. It is the hosts, most recently connected first,
     * which is what a palette opened by reflex is nearly always for — so
     * [entries] is expected to carry its hosts in that order.
     */
    fun rank(entries: List<PaletteEntry>, query: String, limit: Int = 40): List<PaletteMatch> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return entries.asSequence()
                .filter { it.kind == PaletteKind.HOST }
                .take(RECENT)
                .map { PaletteMatch(it, 0) }
                .toList()
        }
        return entries.asSequence()
            .mapNotNull { score(it, q) }
            .sortedWith(
                compareByDescending<PaletteMatch> { it.score }
                    .thenBy { it.entry.kind.ordinal }
                    .thenBy { it.entry.title.lowercase() },
            )
            .take(limit)
            .toList()
    }

    /**
     * The same matches under one heading per kind, the kinds ordered by their
     * best result.
     *
     * The list stays a ranked list — whatever the query is really about leads —
     * while a heading still says what each run of rows is.
     */
    fun sections(matches: List<PaletteMatch>): List<Pair<PaletteKind, List<PaletteMatch>>> =
        matches.groupBy { it.entry.kind }
            .toList()
            .sortedByDescending { (_, rows) -> rows.first().score }

    private fun score(entry: PaletteEntry, q: String): PaletteMatch? {
        val title = entry.title.lowercase()
        if (title.startsWith(q)) {
            // Among titles that all begin with what was typed, the shortest is
            // the one that is most nearly the answer.
            val score = (TITLE_PREFIX - title.length).coerceAtLeast(TITLE_PREFIX - TIER)
            return PaletteMatch(entry, score, (0 until q.length).toList())
        }
        subsequence(title, q)?.let { hits ->
            // A tight match is worth more than an early one: "tab" in "Session
            // tabs" is the word, while the t, a and b scattered through "Tell me
            // about the bell" are an accident of spelling.
            val gaps = hits.last() - hits.first() + 1 - q.length
            val score = (TITLE_SUBSEQUENCE - hits.first() * 2 - gaps * 8).coerceAtLeast(TITLE_SUBSEQUENCE - TIER)
            return PaletteMatch(entry, score, hits)
        }
        // Last resort: the words under the title. Somebody who remembers "the
        // one about the wallpaper" and not "Material You" has to be able to
        // find it, and the subsequence runs across both so a query can start in
        // the title and finish in the description.
        val subtitle = entry.subtitle.lowercase()
        if (subtitle.isEmpty()) return null
        val hits = subsequence("$title $subtitle", q) ?: return null
        val gaps = hits.last() - hits.first() + 1 - q.length
        val score = (DESCRIPTION - hits.first() - gaps).coerceAtLeast(DESCRIPTION - TIER)
        val split = entry.title.length
        return PaletteMatch(
            entry,
            score,
            titleHits = hits.filter { it < split },
            subtitleHits = hits.filter { it > split }.map { it - split - 1 },
        )
    }

    /**
     * Where each character of [query] sits in [haystack], or null when they do
     * not all appear in order.
     *
     * Leftmost and greedy: "nrdgl" lands on the n, r and d of Nerd and the g
     * and l of glyphs. A cleverer search could find a tighter set of positions,
     * but the first one found is the one a person reading the bold characters
     * would have picked out anyway.
     */
    private fun subsequence(haystack: String, query: String): List<Int>? {
        val hits = ArrayList<Int>(query.length)
        var from = 0
        for (c in query) {
            val at = haystack.indexOf(c, from)
            if (at < 0) return null
            hits += at
            from = at + 1
        }
        return hits
    }
}

/**
 * Everything the palette can find that is not a host or a snippet.
 *
 * There is no reflection over Compose, so a settings row exists here because
 * somebody wrote it here: **a new setting has to be added to this list, or the
 * palette will not know about it.** The cost of that is one line; the cost of
 * the alternative was remembering which of ten sections a switch lives in.
 */
object PaletteCatalog {

    /** Every row in every settings section, each jumping to the section holding it. */
    fun settings(): List<PaletteEntry> = listOf(
        setting(R.string.appearancesettings_app_theme, R.string.palette_light_dark_or_whatever_the_system_is_doing, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_material_you_colors, R.string.appearancesettings_tint_the_app_with_your_wallpaper_palette, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_font_size, R.string.palette_how_big_the_terminal_draws_pinching_changes, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_font, R.string.palette_the_monospaced_family_the_terminal_draws_wit, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_ligatures, R.string.palette_join_into_single_glyphs, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_nerd_font_glyphs, R.string.appearancesettings_draw_prompt_and_file_icons_from_the_bundled_symb, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_color_scheme, R.string.palette_the_terminal_palette_hosts_and_groups_can_ov, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_bold_text_is_bright, R.string.appearancesettings_draw_bold_text_in_the_brighter_colors_the_way_xt, Routes.SETTINGS_APPEARANCE),
        setting(R.string.palette_cursor_shape, R.string.palette_block_bar_or_underline, Routes.SETTINGS_APPEARANCE),
        setting(R.string.palette_cursor_blink, R.string.appearancesettings_pauses_while_you_type_and_while_the_terminal_is, Routes.SETTINGS_APPEARANCE),
        setting(R.string.appearancesettings_highlighting, R.string.palette_keyword_rules_that_recolor_lines_as_they_are, Routes.HIGHLIGHTS),
        setting(R.string.extrakeysscreen_extra_keys, R.string.palette_the_bar_above_the_keyboard_which_keys_in_whi, Routes.EXTRA_KEYS),
        setting(R.string.chordsscreen_chords, R.string.keyboardsettings_the_tmux_ctrl_and_agent_keys_on_the_sheet_you_ge, Routes.CHORDS),
        setting(R.string.hosteditscreen_keyboard_protocol, R.string.palette_lets_a_program_see_the_difference_between_ct, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_type_with_the_app_s_keyboard, R.string.palette_a_plain_layout_with_ctrl_on_the_bottom_row_d, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_ctrl_keys_always_send_control_bytes, R.string.palette_ctrl_c_still_interrupts_a_program_that_has_t, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_keep_the_compose_line_open, R.string.palette_the_field_and_what_was_being_written_in_it_s, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_caps_lock_acts_as, R.string.palette_escape_ctrl_or_caps_lock, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_double_tap_locks_a_modifier, R.string.palette_tap_ctrl_twice_and_it_stays_down_until_you_t, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_double_tap_sends_tab, R.string.palette_the_key_a_phone_keyboard_hides_two_taps_away, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_two_finger_drag_sends_arrows, R.string.keyboardsettings_slide_two_fingers_to_walk_the_cursor_pinch_still, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_hold_ctrl_for_chords, R.string.palette_a_long_press_on_ctrl_opens_the_chords_sheet, Routes.SETTINGS_KEYBOARD),
        setting(R.string.keyboardsettings_swipe_between_sessions, R.string.keyboardsettings_drag_sideways_in_the_terminal_to_move_along_the, Routes.SETTINGS_KEYBOARD),
        setting(R.string.terminalsettings_scrollback, R.string.palette_how_many_lines_a_session_keeps_behind_the_sc, Routes.SETTINGS_TERMINAL),
        setting(R.string.terminalsettings_redraw_limit, R.string.palette_caps_repaints_under_heavy_output_to_save_bat, Routes.SETTINGS_TERMINAL),
        setting(R.string.hosteditscreen_inline_images, R.string.terminalsettings_pictures_drawn_in_the_terminal_by_chafa_timg_or, Routes.SETTINGS_TERMINAL),
        setting(R.string.terminalsettings_predictive_echo, R.string.palette_mosh_can_draw_a_keystroke_before_the_server, Routes.SETTINGS_TERMINAL),
        setting(R.string.terminalsettings_complete_from_history, R.string.palette_the_rest_of_a_command_you_have_run_here_in_g, Routes.SETTINGS_TERMINAL),
        setting(R.string.terminalsettings_tab_takes_the_suggestion, R.string.terminalsettings_only_while_one_is_showing_otherwise_tab_is_the_s, Routes.SETTINGS_TERMINAL),
        setting(R.string.terminalsettings_session_recordings, R.string.palette_record_every_session_and_in_which_format, Routes.SETTINGS_TERMINAL),
        setting(R.string.sessionssettings_session_tabs, R.string.palette_the_strip_of_names_under_the_terminal_s_bar, Routes.SETTINGS_SESSIONS),
        setting(R.string.sessionssettings_reopen_sessions, R.string.palette_after_the_app_is_killed_what_was_open_is_dia, Routes.SETTINGS_SESSIONS),
        setting(R.string.sessionssettings_keep_floating_when_you_leave, R.string.palette_switching_apps_leaves_the_terminal_in_a_smal, Routes.SETTINGS_SESSIONS),
        setting(R.string.hosteditscreen_tmux_controls, R.string.palette_the_chords_sheet_the_window_list_and_the_sid, Routes.SETTINGS_SESSIONS),
        setting(R.string.sessionssettings_keep_screen_on, R.string.palette_while_a_terminal_is_open, Routes.SETTINGS_SESSIONS),
        setting(R.string.sessionssettings_notify_on_bell, R.string.sessionssettings_when_the_app_is_in_the_background, Routes.SETTINGS_SESSIONS),
        setting(R.string.sessionssettings_vibrate_on_bell, R.string.palette_a_buzz_to_go_with_it, Routes.SETTINGS_SESSIONS),
        setting(R.string.sessionssettings_long_command_finished, R.string.palette_a_notification_when_a_command_over_30_second, Routes.SETTINGS_SESSIONS),
        setting(R.string.sessionssettings_let_programs_raise_a_notification, R.string.palette_a_script_on_the_server_can_ask_for_one_with, Routes.SETTINGS_SESSIONS),
        setting(R.string.connectionssettings_keepalive, R.string.palette_how_often_an_idle_session_pokes_the_server, Routes.SETTINGS_CONNECTIONS),
        setting(R.string.connectionssettings_data_saver, R.string.palette_holds_transfers_for_wi_fi_and_spaces_out_kee, Routes.SETTINGS_CONNECTIONS),
        setting(R.string.connectionssettings_ask_before_agent_signing, R.string.palette_a_forwarded_key_asks_before_it_signs, Routes.SETTINGS_CONNECTIONS),
        setting(R.string.connectionssettings_find_hosts_on_this_network, R.string.connectionssettings_servers_that_announce_ssh_on_this_network_appear, Routes.SETTINGS_CONNECTIONS),
        setting(R.string.connectionssettings_show_the_server_s_message, R.string.palette_what_a_server_prints_before_login, Routes.SETTINGS_CONNECTIONS),
        setting(R.string.connectionssettings_learn_the_host_s_shell_history, R.string.palette_read_its_history_so_it_completes_what_you_ty, Routes.SETTINGS_CONNECTIONS),
        setting(R.string.palette_resolver, R.string.palette_which_dns_a_tunnelled_name_is_asked_of, Routes.SETTINGS_CONNECTIONS),
        setting(R.string.filessettings_open_a_text_file_with, R.string.palette_the_built_in_editor_or_an_app_on_the_phone, Routes.SETTINGS_FILES),
        setting(R.string.filessettings_keep_a_bak, R.string.filessettings_copy_the_file_on_the_host_before_saving_over_it, Routes.SETTINGS_FILES),
        setting(R.string.filessettings_show_hidden_files, R.string.filessettings_dotfiles_in_the_sftp_browser, Routes.SETTINGS_FILES),
        setting(R.string.filessettings_where_files_dropped_on_the_terminal_go, R.string.palette_the_folder_an_upload_lands_in, Routes.SETTINGS_FILES),
        setting(R.string.backupdialogs_back_up_to_a_file, R.string.palette_hosts_keys_snippets_and_settings_sealed_with, Routes.SETTINGS_BACKUP),
        setting(R.string.backupsettings_restore_from_a_file, R.string.backupsettings_adds_what_the_file_has_nothing_here_is_deleted, Routes.SETTINGS_BACKUP),
        setting(R.string.securitysettings_app_lock, R.string.securitysettings_fingerprint_face_or_screen_lock_before_hosts_and, Routes.SETTINGS_SECURITY),
        setting(R.string.securitysettings_lock_again_after, R.string.palette_how_long_a_trip_to_another_app_may_last, Routes.SETTINGS_SECURITY),
        setting(R.string.automationsettings_let_other_apps_drive_sessions, R.string.palette_tasker_automate_and_adb_can_connect_run_a_co, Routes.SETTINGS_AUTOMATION),
        setting(R.string.palette_recent_calls_from_other_apps, R.string.palette_the_last_ten_with_the_app_that_made_them, Routes.SETTINGS_AUTOMATION),
        setting(R.string.palette_about, R.string.palette_version_licenses_and_what_the_core_is_built, Routes.SETTINGS_ABOUT),
    )

    /** The things the app does that are not a setting. */
    fun actions(): List<PaletteEntry> = listOf(
        action(R.string.hostsscreen_new_host, R.string.palette_add_a_server, Routes.hostEdit("new")),
        action(R.string.keysscreen_keys, R.string.palette_ssh_keys_on_this_phone_generate_import_repla, Routes.KEYS),
        action(R.string.accountsscreen_accounts, R.string.palette_a_login_several_hosts_share, Routes.ACCOUNTS),
        action(R.string.groupsscreen_groups, R.string.palette_folders_that_hand_their_hosts_a_jump_host_a, Routes.GROUPS),
        action(R.string.snippetsheet_snippets, R.string.palette_commands_worth_keeping_typed_into_a_session, Routes.SNIPPETS),
        action(R.string.hostsscreen_vpns_and_proxies, R.string.palette_wireguard_tunnels_tailscale_accounts_and_sav, Routes.TUNNELS),
        action(R.string.palette_known_hosts, R.string.palette_the_host_keys_this_phone_has_trusted, Routes.KNOWN_HOSTS),
        action(R.string.hostsscreen_transfers, R.string.hostsscreen_files_on_their_way_to_or_from_a_host, Routes.TRANSFERS),
        action(R.string.recordingsscreen_recordings, R.string.terminalsettings_play_back_a_recording_read_a_log_share_or_delete, Routes.RECORDINGS),
        action(R.string.broadcastscreen_run_on_many_hosts, R.string.hostsscreen_one_command_across_several_servers_each_answer_b, Routes.BROADCAST),
        action(R.string.appearancesettings_color_scheme, R.string.palette_preview_every_scheme_as_a_real_session, Routes.THEME),
        action(R.string.settingsscreen_settings, R.string.palette_the_index_of_all_of_it, Routes.SETTINGS),
    )

    private fun setting(title: Int, subtitle: Int, route: String) =
        PaletteEntry("", "", PaletteKind.SETTING, route, titleRes = title, subtitleRes = subtitle)

    private fun action(title: Int, subtitle: Int, route: String) =
        PaletteEntry("", "", PaletteKind.ACTION, route, titleRes = title, subtitleRes = subtitle)
}
