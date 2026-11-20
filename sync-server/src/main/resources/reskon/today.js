/* Today's habits widget page (docs/reskon-plugin.md). Reskon frames this
   page; it exchanges the ticket after "#" for a session kept in
   sessionStorage, lists today's habits as checkboxes, and tells Reskon when
   data changes. It acts on messages only from Reskon's own address. */
(function () {
  var RESKON = __RESKON_ORIGIN__;
  var TZ = "";
  try { TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || ""; } catch (e) {}
  var session = sessionStorage.getItem("reskon-session") || "";
  var msg = document.getElementById("msg");
  var list = document.getElementById("list");

  var params = new URLSearchParams(location.hash.slice(1));
  var ticket = params.get("ticket") || "";
  // The ticket works once: take it out of the address straight away.
  history.replaceState(null, "", location.pathname + location.search);

  function applyTheme(t) {
    if (!t || typeof t !== "object") return;
    var vars = { bg: "--bg", surface: "--surface", text: "--text", muted: "--muted", accent: "--accent", border: "--border", font: "--font" };
    for (var k in vars) if (typeof t[k] === "string") document.documentElement.style.setProperty(vars[k], t[k]);
    if (typeof t.radius === "number") document.documentElement.style.setProperty("--radius", t.radius + "px");
  }
  try { applyTheme(JSON.parse(params.get("theme") || "null")); } catch (e) {}

  function remember(value) {
    session = value;
    try { sessionStorage.setItem("reskon-session", value); } catch (e) {}
  }

  function call(method, path, body) {
    return fetch(path, {
      method: method,
      headers: { "Content-Type": "application/json", "Authorization": "Bearer " + session },
      body: body ? JSON.stringify(body) : undefined,
    }).then(function (r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    });
  }

  function show(data) {
    msg.textContent = "";
    list.textContent = "";
    (data.habits || []).forEach(function (h) {
      var li = document.createElement("li");
      var label = document.createElement("label");
      var box = document.createElement("input");
      box.type = "checkbox";
      box.checked = !!h.done;
      label.className = h.done ? "done" : "";
      var name = document.createElement("span");
      name.textContent = h.label;
      box.addEventListener("change", function () {
        var wanted = box.checked;
        box.disabled = true;
        call("POST", "../api/reskon/page/today", { key: h.key, done: wanted, tz: TZ }).then(function (d) {
          show(d);
          if (RESKON) window.parent.postMessage({ reskon: 1, type: "changed" }, RESKON);
        }, function () {
          msg.textContent = "Could not save that tick.";
          box.checked = !wanted;
          box.disabled = false;
        });
      });
      label.appendChild(box);
      label.appendChild(name);
      li.appendChild(label);
      list.appendChild(li);
    });
    if (!(data.habits || []).length) {
      msg.textContent = data.run_ended ? "Run completed! Start your next run in Habit Lab." : "No habits today.";
    }
  }

  function load() {
    return call("GET", "../api/reskon/page/today?tz=" + encodeURIComponent(TZ)).then(show, function (e) {
      if (e && e.message === "401") {
        // The session aged out; the next ticket from Reskon signs back in.
        remember("");
        msg.textContent = "Press Refresh on the widget to sign in again.";
      } else {
        msg.textContent = "Could not load today's habits.";
      }
    });
  }

  window.addEventListener("message", function (e) {
    if (!RESKON || e.origin !== RESKON || !e.data || e.data.reskon !== 1) return;
    if (e.data.type === "theme") applyTheme(e.data.theme);
    if (e.data.type === "refresh" && session) load();
  });

  function start() {
    if (session) { load(); return; }
    if (!ticket) {
      msg.textContent = "Open this page from a Reskon dashboard.";
      return;
    }
    fetch("../api/reskon/session", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ticket: ticket }),
    }).then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
      .then(function (d) { remember(d.session); return load(); }, function () {
        msg.textContent = "This sign-in link is used up or too old. Press Refresh on the widget.";
      });
  }
  start();
})();
