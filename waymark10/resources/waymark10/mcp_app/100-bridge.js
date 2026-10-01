// The MCP Apps bridge, by hand: JSON-RPC 2.0 over postMessage to the
// host (docs/spec-mcp-apps.md § 4). The page holds no token. EVERY row
// value is written with textContent; none becomes HTML or an attribute.
(function () {
  "use strict";
  var app = document.getElementById("app"), waiting = {}, nextId = 1;
  var subject = null, view = null, said = "Reading the row…", refused = false;

  function send(msg) { window.parent.postMessage(msg, "*"); }
  function request(method, params) {
    return new Promise(function (resolve, reject) {
      waiting[nextId] = { resolve: resolve, reject: reject };
      send({ jsonrpc: "2.0", id: nextId++, method: method, params: params || {} });
    });
  }
  function el(tag, text, cls) {
    var n = document.createElement(tag);
    if (text != null)
      n.textContent = typeof text === "string" ? text : JSON.stringify(text, null, 2);
    if (cls) n.className = cls;
    return n;
  }
  function detailOf(result) {
    var text = ((result.content || [])[0] || {}).text || "";
    try { return JSON.parse(text).detail || text; } catch (_e) { return text; }
  }
  function failed(e) {
    said = (e && e.message) || "The host refused.";
    refused = true;
    render();
  }
  // A scope, one line per entry: the kind, its actions or "read only",
  // then the ids and the filter that narrow it.
  function scopeOf(entries) {
    var dd = el("dd"), ul = el("ul");
    entries.forEach(function (e) {
      var entry = e || {}, actions = entry.actions || [];
      var line = String(entry.kind || "") + ": " +
        (actions.length ? actions.join(", ") : "read only");
      if (entry.ids && entry.ids.length) line += " · ids " + entry.ids.join(", ");
      if (entry.filter)
        line += " · filter " + (typeof entry.filter === "string"
          ? entry.filter : JSON.stringify(entry.filter));
      ul.appendChild(el("li", line));
    });
    dd.appendChild(ul);
    return dd;
  }
  // A value that is not text: collapsed, with the JSON for whoever opens it.
  function jsonOf(value) {
    var dd = el("dd"), details = el("details");
    details.appendChild(el("summary", "show"));
    details.appendChild(el("pre", value));
    details.addEventListener("toggle", resized);
    dd.appendChild(details);
    return dd;
  }
  function chipOf(state) {
    if (state === "held") return "chip warning";
    if (["allowed", "done", "approved"].indexOf(state) >= 0) return "chip ok";
    if (["refused", "failed", "expired", "denied"].indexOf(state) >= 0)
      return "chip danger";
    return "chip";
  }
  // One door and its own inputs, as one group: the button, then a box
  // for each input the read names. The placeholder says what the box is.
  function doorOf(door, parent) {
    var lengths = door.lengths || {}, required = door.required || [];
    var group = el("div", null, "door");
    var b = el("button", door.label, door.style === "danger" ? "danger" : "primary");
    group.appendChild(b);
    var boxes = (door.inputs || []).map(function (name) {
      var box = el("input"), words = name === "reason" ? "why" : name;
      box.type = "text";
      box.placeholder = required.indexOf(name) < 0 ? words + ", optional" : words;
      box.setAttribute("aria-label", door.label + ": " + name);
      if (lengths[name]) box.maxLength = lengths[name];
      group.appendChild(box);
      return [name, box];
    });
    parent.appendChild(group);
    b.addEventListener("click", function () {
      var input = {};
      boxes.forEach(function (nb) { if (nb[1].value) input[nb[0]] = nb[1].value; });
      // a confirm door's sentence is the read's own, never typed here
      act(door.action, input, door.consequence);
    });
  }
  function resized() {
    send({ jsonrpc: "2.0", method: "ui/notifications/size-changed",
           params: { height: document.documentElement.scrollHeight } });
  }
  function render() {
    app.replaceChildren();
    if (view) {
      var head = el("h3", view.title || view.summary, "title");
      var dl = el("dl"), row = el("div", null, "doors"), cards = [];
      var link = view.link;
      if (view.state) head.appendChild(el("span", view.state, chipOf(view.state)));
      (view.fields || []).forEach(function (f) {
        dl.appendChild(el("dt", f.label));
        dl.appendChild(f.label === "scope" && Array.isArray(f.value)
          ? scopeOf(f.value)
          : f.value !== null && typeof f.value === "object"
            ? jsonOf(f.value) : el("dd", String(f.value)));
      });
      (view.doors || []).forEach(function (d) {
        if (!d.available) return;
        if (!d.consequence) { doorOf(d, row); return; }
        // a confirm door: its consequence sentence, on the button's card
        var card = el("div", null, "card");
        card.appendChild(el("p", d.consequence));
        doorOf(d, card);
        cards.push(card);
      });
      app.appendChild(head);
      if (dl.firstChild) app.appendChild(dl);
      cards.forEach(function (c) { app.appendChild(c); });
      if (row.firstChild) app.appendChild(row);
      if (link) {
        // the one address the page knows: the row's own, built by the server
        var open = el("button", "Open in Waymark", "open");
        open.addEventListener("click", function () {
          request("ui/open-link", { url: link }).catch(failed);
        });
        app.appendChild(open);
      }
    }
    // a refusal is the engine's own sentence, marked apart from the line
    // it makes after a tap
    if (said) app.appendChild(el("p", said, refused ? "refusal" : "said"));
    resized();
  }
  function read() {
    return request("tools/call", { name: "waymark_app_read", arguments: subject })
      .then(function (result) {
        view = result.isError ? null : result.structuredContent;
        if (result.isError) { said = detailOf(result); refused = true; }
        render();
      }, failed);
  }
  function act(action, input, acknowledge) {
    var args = { kind: subject.kind, id: subject.id, action: action, input: input,
                 ticket: view.ticket };
    if (acknowledge) args.acknowledge = acknowledge;
    request("tools/call", { name: "waymark_app_act", arguments: args })
      .then(function (result) {
        refused = !!result.isError;
        said = result.isError ? detailOf(result) : result.structuredContent.line;
        // one engine-made line for the model, and never ui/message
        if (!result.isError)
          request("ui/update-model-context", { content: [{ type: "text", text: said }] })
            .catch(function () {});
        return read();
      }, failed);
  }
  window.addEventListener("message", function (ev) {
    var msg = ev.data;
    if (ev.source !== window.parent || !msg || msg.jsonrpc !== "2.0") return;
    if (msg.method === "ui/notifications/tool-input") {
      var a = (msg.params || {}).arguments || {};
      subject = { kind: String(a.kind || ""), id: String(a.id || "") };
      said = null;
      refused = false;
      read();
    } else if (msg.method === "ui/resource-teardown") {
      send({ jsonrpc: "2.0", id: msg.id, result: {} });
    } else if (!msg.method && waiting[msg.id]) {
      var w = waiting[msg.id];
      delete waiting[msg.id];
      if (msg.error) w.reject(msg.error); else w.resolve(msg.result);
    }
  });
  render();
  request("ui/initialize", { protocolVersion: "2026-01-26", appCapabilities: {},
                             appInfo: { name: "waymark-row", version: "1" } })
    .then(function () {
      send({ jsonrpc: "2.0", method: "ui/notifications/initialized", params: {} });
    }, failed);
})();
