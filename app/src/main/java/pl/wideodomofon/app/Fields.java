package pl.wideodomofon.app;

/** Opis pol ustawien wyswietlanych w menu aplikacji (odpowiednik formularza z panelu www). */
final class Fields {
    static final class F {
        final String key, type, label, hint;
        final String[] values, labels;

        F(String key, String type, String label, String hint, String[] values, String[] labels) {
            this.key = key;
            this.type = type;
            this.label = label;
            this.hint = hint;
            this.values = values;
            this.labels = labels;
        }
    }

    static final class Group {
        final String title;
        final F[] fields;

        Group(String title, F... fields) {
            this.title = title;
            this.fields = fields;
        }
    }

    private Fields() {}

    private static F text(String key, String label, String hint) {
        return new F(key, "text", label, hint, null, null);
    }

    private static F pass(String key, String label, String hint) {
        return new F(key, "password", label, hint, null, null);
    }

    private static F num(String key, String label, String hint) {
        return new F(key, "number", label, hint, null, null);
    }

    private static F bool(String key, String label, String hint) {
        return new F(key, "bool", label, hint, null, null);
    }

    /** options: pary "wartosc=Opis". */
    private static F sel(String key, String label, String hint, String... options) {
        String[] v = new String[options.length], l = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            int p = options[i].indexOf('=');
            v[i] = options[i].substring(0, p);
            l[i] = options[i].substring(p + 1);
        }
        return new F(key, "select", label, hint, v, l);
    }

    static Group[] all() {
        return new Group[]{
                new Group("Połączenia SIP",
                        num("sip_port", "Port SIP urządzenia", "Bramofon może dzwonić bezpośrednio na adres IP i ten port (bez serwera)."),
                        bool("sip_enabled", "Rejestracja na serwerze SIP", "Potrzebna, gdy urządzenie ma działać poza siecią domową (np. telefon)."),
                        text("sip_server", "Serwer SIP", "Adres lub adres:port"),
                        text("sip_user", "Użytkownik / numer", ""),
                        pass("sip_pass", "Hasło SIP", ""),
                        sel("sip_transport", "Transport", "", "udp=UDP", "tcp=TCP"),
                        num("sip_expires", "Odświeżanie rejestracji [s]", "")),
                new Group("Obraz z kamery",
                        sel("video_source", "Źródło obrazu", "RTSP pokazuje obraz od pierwszej sekundy dzwonienia.",
                                "sip=Wideo w połączeniu SIP", "rtsp=Strumień RTSP z bramofonu", "jpeg=Zdjęcia JPEG (zapasowe)"),
                        bool("early_media", "Obraz SIP przed odebraniem", ""),
                        text("rtsp_url", "Adres RTSP", "rtsp://uzytkownik:haslo@IP_BRAMOFONU/live/ch00_0"),
                        bool("rtsp_tcp", "RTSP przez TCP", ""),
                        text("jpeg_url", "Adres zdjęcia JPEG", "http://IP_BRAMOFONU:8080/picture.jpg")),
                new Group("Furtka",
                        sel("gate_mode", "Sposób otwierania", "", "both=HTTP i DTMF", "http=Tylko HTTP", "dtmf=Tylko DTMF"),
                        text("http_url", "Adres URL otwarcia", "http://IP_BRAMOFONU/fcgi/OpenDoor?action=OpenDoor&DoorNum=1"),
                        text("http_user", "Użytkownik HTTP", ""),
                        pass("http_pass", "Hasło HTTP", ""),
                        text("dtmf_code", "Kod DTMF", "Akuvox domyślnie: #"),
                        sel("dtmf_method", "Sposób wysyłania DTMF", "Taki sam jak w bramofonie.",
                                "rfc2833=RFC2833", "info=SIP INFO", "both=RFC2833 + SIP INFO"),
                        num("hangup_after_open_s", "Rozłącz po otwarciu po [s]", "0 = nie rozłączaj automatycznie.")),
                new Group("Dźwięk i rozmowa",
                        bool("ring_enabled", "Dzwonek", ""),
                        num("ring_volume", "Głośność dzwonka [%]", "-1 = nie zmieniaj głośności urządzenia."),
                        num("call_volume", "Głośność rozmowy [%]", "-1 = nie zmieniaj."),
                        num("mic_gain_db", "Wzmocnienie mikrofonu [dB]", ""),
                        num("speaker_gain_db", "Wzmocnienie głośnika [dB]", ""),
                        bool("echo_cancel", "Redukcja echa", ""),
                        num("ring_timeout_s", "Maks. czas dzwonienia [s]", ""),
                        num("max_call_s", "Maks. czas rozmowy [s]", "0 = bez limitu.")),
                new Group("Zachowanie",
                        bool("restore_prev", "Przywracaj poprzednią aplikację", ""),
                        bool("screen_off_after", "Wygaś ekran po rozmowie", "Wymaga root."),
                        num("nightly_restart_hour", "Nocny restart o godzinie", "0–23, -1 = wyłączony.")),
                new Group("Panel www (opcjonalny)",
                        bool("web_enabled", "Panel www włączony", "Konfiguracja przez przeglądarkę z tej samej sieci. Na telefonie domyślnie wyłączony."),
                        num("web_port", "Port panelu", ""),
                        text("web_pass", "Hasło panelu (login: admin)", ""))
        };
    }
}
