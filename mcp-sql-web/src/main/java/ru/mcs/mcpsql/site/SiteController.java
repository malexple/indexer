package ru.mcs.mcpsql.site;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The English page is the root page; the Russian page lives under /ru/. */
@Controller
public class SiteController {

    @GetMapping({"/ru", "/ru/"})
    public String russian() {
        return "forward:/ru/index.html";
    }
}
