(() => {
  const titles = {
    library: "书架",
    search: "搜书",
    reader: "阅读器",
    downloads: "下载",
    sources: "书源",
  };

  const hints = {
    library: "书架：点「继续阅读」直接翻到上次停下的那一页。",
    search: "搜书：输入关键词，点搜索，看看 4 个来源如何合并成一张卡片。",
    reader: "阅读器：切换字号、纸张/日间/夜间主题，再试试分页与滚动。",
    downloads: "下载：暂停或继续任务；已完成的书可以在断网时打开。",
    sources: "书源：开关规则，或点「测试全部」看健康状态变化。",
  };

  const hint = document.getElementById("demo-hint");
  const devices = Array.from(document.querySelectorAll(".device"));
  const controllers = [];
  let currentScreen = "library";

  const escapeHtml = (value) =>
    value.replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;",
    }[char]));

  const scanningMarkup = () => `
    <div class="scanning" aria-live="polite">
      <div class="scanning-row"><span>主站 / 搜索中</span><span class="bar"><i></i></span></div>
      <div class="scanning-row"><span>备站 / 搜索中</span><span class="bar"><i></i></span></div>
      <div class="scanning-row"><span>镜像 / 搜索中</span><span class="bar"><i></i></span></div>
    </div>
  `;

  const resultMarkup = (query) => `
    <button class="result-card" data-open="reader" type="button">
      <span class="cover cover-a result-cover"><span>长夜行舟</span></span>
      <span class="result-copy">
        <span class="mono">“${escapeHtml(query)}” · 3 个来源 · 已合并</span>
        <strong>长夜行舟</strong>
        <span>沈砚 · 连载中 · 128 万字</span>
        <span class="result-sources"><i>主站</i><i>备站</i><i>镜像</i></span>
      </span>
      <span class="result-arrow">→</span>
    </button>
  `;

  function createController(device) {
    const screens = Array.from(device.querySelectorAll(".screen"));
    const navButtons = Array.from(device.querySelectorAll(".app-nav button[data-screen]"));
    const appTitle = device.querySelector(".app-title");
    const searchForm = device.querySelector(".search-box");
    const searchInput = device.querySelector(".search-box input");
    const searchResult = device.querySelector(".search-result");
    const readerPage = device.querySelector(".reader-page");
    const modeButton = device.querySelector(".mode-btn");

    let readerSize = 12;
    let mode = "分页";
    let searchTimer = 0;
    let sourceTimer = 0;

    const show = (name, options = {}) => {
      if (!titles[name]) return;
      screens.forEach((screen) => {
        screen.classList.toggle("is-active", screen.dataset.screen === name);
      });
      navButtons.forEach((button) => {
        const active = button.dataset.screen === name;
        button.classList.toggle("is-active", active);
        button.setAttribute("aria-pressed", String(active));
      });
      if (appTitle) appTitle.textContent = titles[name];
      if (name === "search" && options.focus && searchInput) {
        window.setTimeout(() => searchInput.focus(), 160);
      }
      if (name === "reader" && readerPage) readerPage.scrollTop = 0;
      if (name === "downloads") device.querySelectorAll("[data-download]").forEach((row) => {
        row.classList.add("is-visible");
      });
    };

    const runSearch = () => {
      if (!searchResult) return;
      window.clearTimeout(searchTimer);
      searchResult.innerHTML = scanningMarkup();
      searchTimer = window.setTimeout(() => {
        const query = (searchInput && searchInput.value.trim()) || "长夜";
        searchResult.innerHTML = resultMarkup(query);
      }, 950);
    };

    device.addEventListener("click", (event) => {
      const nav = event.target.closest("button[data-screen]");
      if (nav) {
        showScreen(nav.dataset.screen);
        return;
      }

      const jump = event.target.closest("[data-screen-jump]");
      if (jump) {
        showScreen(jump.dataset.screenJump);
        return;
      }

      const open = event.target.closest("[data-open]");
      if (open) {
        showScreen(open.dataset.open);
        setHint("已打开阅读器：这是示例章节，试试右上角的主题与字号。");
        return;
      }

      const fontButton = event.target.closest("[data-font]");
      if (fontButton && readerPage) {
        const delta = Number(fontButton.dataset.font);
        readerSize = Math.min(16, Math.max(10, readerSize + delta));
        readerPage.style.setProperty("--reader-size", `${readerSize}px`);
        return;
      }

      const themeButton = event.target.closest("[data-reader-theme]");
      if (themeButton && readerPage) {
        const theme = themeButton.dataset.readerTheme;
        readerPage.dataset.theme = theme;
        device.querySelectorAll("[data-reader-theme]").forEach((button) => {
          button.classList.toggle("is-active", button === themeButton);
        });
        return;
      }

      if (event.target.closest("#mode-toggle, #mode-toggle-demo")) {
        mode = mode === "分页" ? "滚动" : "分页";
        if (modeButton) modeButton.textContent = mode;
        if (readerPage) readerPage.classList.toggle("is-scroll", mode === "滚动");
        return;
      }

      const downloadToggle = event.target.closest("[data-toggle-download]");
      if (downloadToggle) {
        const row = downloadToggle.closest("[data-download]");
        if (!row || row.classList.contains("is-done")) return;
        const paused = row.classList.toggle("is-paused");
        downloadToggle.textContent = paused ? "继续" : "暂停";
        const note = row.querySelector("small");
        if (note) {
          note.textContent = paused
            ? "已暂停 · 点击继续"
            : row.dataset.running || "下载中";
        }
        return;
      }

      if (event.target.closest("[id^='test-sources']")) {
        const button = event.target.closest("[id^='test-sources']");
        if (button.disabled) return;
        button.disabled = true;
        button.textContent = "测试中…";
        window.clearTimeout(sourceTimer);
        sourceTimer = window.setTimeout(() => {
          device.querySelectorAll("[data-source]").forEach((row) => {
            const input = row.querySelector("input[type='checkbox']");
            const health = row.querySelector("[data-health]");
            if (!input || !health) return;
            if (!input.checked) {
              health.textContent = "已停用";
              health.className = "health";
              return;
            }
            health.textContent = "健康";
            health.className = "health is-ok";
          });
          button.disabled = false;
          button.textContent = "测试全部";
        }, 900);
        return;
      }
    });

    if (searchForm) {
      searchForm.addEventListener("submit", (event) => {
        event.preventDefault();
        runSearch();
      });
    }

    device.querySelectorAll("[data-source] input[type='checkbox']").forEach((input) => {
      input.addEventListener("change", () => {
        const row = input.closest("[data-source]");
        const health = row ? row.querySelector("[data-health]") : null;
        const note = row ? row.querySelector("small") : null;
        if (row) row.classList.toggle("is-off", !input.checked);
        if (health) {
          health.textContent = input.checked ? "健康" : "已停用";
          health.className = input.checked ? "health is-ok" : "health";
        }
        if (note) {
          note.textContent = input.checked
            ? (note.dataset.note || "已启用 · 待测试")
            : "已停用";
        }
      });
    });

    return { show, device };
  }

  function setHint(text) {
    if (hint) hint.textContent = text;
  }

  function showScreen(name, options = {}) {
    if (!titles[name]) return;
    currentScreen = name;
    controllers.forEach((controller) => controller.show(name, options));
    setHint(hints[name] || hints.library);
  }

  devices.forEach((device) => controllers.push(createController(device)));

  document.querySelectorAll("[data-demo]").forEach((button) => {
    button.addEventListener("click", () => {
      const name = button.dataset.demo;
      showScreen(name);
      if (name === "reader") {
        setHint("阅读器：这是示例章节。试试 A− / A+，或切换纸张、日间、夜间主题。");
      }
      if (name === "downloads") {
        setHint("下载：点「暂停」或「继续」，观察任务状态与进度条。");
      }
      if (name === "sources") {
        setHint("书源：开关任意规则，或点「测试全部」查看健康状态。");
      }
    });
  });

  // One gentle progress ticker for the download demo.
  window.setInterval(() => {
    if (document.hidden) return;
    document.querySelectorAll("[data-download]").forEach((row) => {
      if (row.classList.contains("is-paused") || row.classList.contains("is-done")) return;
      const current = Number(row.dataset.progress || 0);
      if (!Number.isFinite(current) || current >= 100) return;
      const next = Math.min(100, current + 0.5);
      row.dataset.progress = String(next);
      const bar = row.querySelector(".bar > i");
      const label = row.querySelector("[data-progress-label]");
      if (bar) bar.style.width = `${next}%`;
      if (label) label.textContent = `${Math.round(next)}%`;
      if (next >= 100) {
        row.classList.add("is-done");
        const button = row.querySelector("[data-toggle-download]");
        if (button) {
          button.textContent = "已读";
          button.disabled = true;
        }
      }
    });
  }, 900);

  const params = new URLSearchParams(window.location.search);
  const initial = params.get("screen");
  if (initial && titles[initial]) {
    showScreen(initial, { focus: false });
  } else {
    setHint(hints.library);
  }

  const scrollTarget = params.get("scroll");
  if (scrollTarget) {
    const target = document.getElementById(scrollTarget);
    if (target) {
      window.setTimeout(() => {
        window.scrollTo({ top: target.getBoundingClientRect().top + window.scrollY - 76, behavior: "auto" });
      }, 80);
    }
  }

  window.showNovelRadarScreen = showScreen;
})();
