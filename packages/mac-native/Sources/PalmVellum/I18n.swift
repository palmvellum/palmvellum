import SwiftUI

/// In-app localization with a runtime language switch (like the PWA's picker),
/// persisted to UserDefaults. Languages match the PWA: en / zh-TW / zh-CN / ja
/// / ko / ru. Strings are stored column-wise in `lang order`.
enum Lang: String, CaseIterable, Identifiable {
    case en, zhTW, zhCN, ja, ko, ru
    var id: String { rawValue }
    var index: Int { Lang.allCases.firstIndex(of: self) ?? 0 }
    var display: String {
        switch self {
        case .en: return "English"
        case .zhTW: return "繁體中文"
        case .zhCN: return "简体中文"
        case .ja: return "日本語"
        case .ko: return "한국어"
        case .ru: return "Русский"
        }
    }
}

@MainActor
final class I18n: ObservableObject {
    @Published var lang: Lang {
        didSet { UserDefaults.standard.set(lang.rawValue, forKey: "appLang") }
    }

    init() {
        if let raw = UserDefaults.standard.string(forKey: "appLang"), let l = Lang(rawValue: raw) {
            lang = l
        } else if let sys = Locale.preferredLanguages.first {
            // best-effort match to a supported language at first launch
            if sys.hasPrefix("zh-Hant") || sys.hasPrefix("zh-TW") || sys.hasPrefix("zh-HK") { lang = .zhTW }
            else if sys.hasPrefix("zh") { lang = .zhCN }
            else if sys.hasPrefix("ja") { lang = .ja }
            else if sys.hasPrefix("ko") { lang = .ko }
            else if sys.hasPrefix("ru") { lang = .ru }
            else { lang = .en }
        } else { lang = .en }
    }

    func t(_ key: String) -> String {
        guard let row = Self.table[key] else { return key }
        let i = lang.index
        return (i < row.count && !row[i].isEmpty) ? row[i] : row[0]
    }

    // Column order: en, zh-TW, zh-CN, ja, ko, ru
    static let table: [String: [String]] = [
        // App / screen titles
        "app": ["PalmVellum Organizers", "PalmVellum Organizers", "PalmVellum Organizers",
                "PalmVellum Organizers", "PalmVellum Organizers", "PalmVellum Organizers"],
        "datebook": ["Date Book", "行事曆", "行事历", "予定表", "일정", "Календарь"],
        "todo": ["To Do", "待辦", "待办", "やること", "할 일", "Дела"],
        "address": ["Address", "通訊錄", "通讯录", "アドレス", "주소록", "Адреса"],
        "memo": ["Memo", "備忘", "备忘", "メモ", "메모", "Заметки"],
        "notepad": ["Note Pad", "記事簿", "记事簿", "手書き", "노트", "Блокнот"],
        "expense": ["Expense", "開支", "开支", "経費", "지출", "Расходы"],
        "mail": ["Mail", "郵件", "邮件", "メール", "메일", "Почта"],
        "hotsync": ["HotSync", "HotSync", "HotSync", "HotSync", "HotSync", "HotSync"],
        "settings": ["Settings", "設定", "设置", "設定", "설정", "Настройки"],
        "conflicts": ["Conflicts", "衝突", "冲突", "競合", "충돌", "Конфликты"],
        // Common actions
        "new": ["+ new", "+ 新增", "+ 新增", "+ 新規", "+ 새로", "+ Создать"],
        "save": ["save", "儲存", "保存", "保存", "저장", "Сохранить"],
        "cancel": ["cancel", "取消", "取消", "キャンセル", "취소", "Отмена"],
        "delete": ["delete", "刪除", "删除", "削除", "삭제", "Удалить"],
        "deleteAll": ["delete all", "全部刪除", "全部删除", "すべて削除", "모두 삭제", "Удалить все"],
        "back": ["back", "返回", "返回", "戻る", "뒤로", "Назад"],
        "edit": ["edit", "編輯", "编辑", "編集", "편집", "Изменить"],
        "today": ["today", "今日", "今日", "今日", "오늘", "Сегодня"],
        // Filters
        "open": ["Open", "未完", "未完", "未完了", "진행", "Открытые"],
        "done": ["Done", "完成", "完成", "完了", "완료", "Готово"],
        "all": ["All", "全部", "全部", "すべて", "전체", "Все"],
        "agenda": ["agenda", "議程", "议程", "予定", "아젠다", "Список"],
        "week": ["week", "週", "周", "週", "주", "Неделя"],
        "month": ["month", "月", "月", "月", "월", "Месяц"],
        "inbox": ["Inbox", "收件匣", "收件箱", "受信", "받은편지", "Входящие"],
        "sources": ["Sources", "訂閱源", "订阅源", "ソース", "소스", "Источники"],
        // Settings
        "account": ["Account", "帳號", "账号", "アカウント", "계정", "Аккаунт"],
        "preferences": ["Preferences", "偏好", "偏好", "環境設定", "환경설정", "Параметры"],
        "language": ["language", "語言", "语言", "言語", "언어", "Язык"],
        "signIn": ["sign in", "登入", "登录", "サインイン", "로그인", "Войти"],
        "signOut": ["sign out", "登出", "登出", "サインアウト", "로그아웃", "Выйти"],
        "syncNow": ["sync now", "立即同步", "立即同步", "今すぐ同期", "지금 동기화", "Синхронизировать"],
        "weekStartsMonday": ["week starts on Monday", "一週由星期一開始", "一周从星期一开始",
                             "週の始まりを月曜に", "월요일에 주 시작", "Неделя с понедельника"],
        "calendarSubs": ["Calendar subscriptions", "行事曆訂閱", "日历订阅", "カレンダー購読", "캘린더 구독", "Подписки календаря"],
    ]
}
