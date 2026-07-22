/* ── the worksheet round-trip: the filtered view leaves as xlsx, the
   edited file comes back and every cell diff replays as the kind's
   own actions (the server's report says exactly what happened) ────── */
const XLSX_MIME =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
async function downloadWorksheet(href) {
  try {
    const res = await fetch(href, {headers: principalHeaders()});
    if (!res.ok) {
      const b = await res.json().catch(() => null);
      toast(b?.detail || `couldn't build the worksheet — ${res.status}`);
      return;
    }
    const blob = await res.blob();
    const plural = href.replace(/^\/api\//, "").split("/")[0];
    const obj = URL.createObjectURL(blob);
    const a = el("a", {href: obj, download: `${plural}.xlsx`});
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(obj), 10000);
  } catch { toast("couldn't reach the worksheet route"); }
}
/* choose a file → POST stages it as a worksheet ROW (the plan is
   already recorded on the 201) → navigate there; review, revalidate,
   and apply are the row's own actions, not a dialog's */
function pickWorksheet(href) {
  const input = el("input", {type:"file", accept:".xlsx",
                             style:"display:none"});
  input.addEventListener("change", async () => {
    const f = input.files && input.files[0];
    input.remove();
    if (!f) return;
    const sep = href.includes("?") ? "&" : "?";
    try {
      const res = await fetch(href + sep + "filename="
                              + encodeURIComponent(f.name),
        {method: "POST",
         headers: {...principalHeaders(), "Content-Type": XLSX_MIME},
         body: f});
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        toast(body?.detail || body?.title
              || `upload failed — ${res.status}`);
        return;
      }
      toast(`${f.name} staged ✓`);
      if (body?.self) go(body.self);
    } catch { toast("couldn't reach the worksheet route"); }
  });
  document.body.append(input);
  input.click();
}
/* the worksheet row's report: the plan while staged, the outcomes
   once applied — one line per spreadsheet line, joined with that
   line's staged cells so the data under judgment is on the page */
function worksheetReport(doc) {
  const rows = doc.data?.report || [];
  const lines = doc.data?.lines || [];
  const cellsByLine = new Map(lines.map(l => [l.line, l.cells || {}]));
  const cellKeys = [];
  for (const l of lines)
    for (const k of Object.keys(l.cells || {}))
      if (!cellKeys.includes(k)) cellKeys.push(k);
  const tally = Object.entries(doc.data?.tally || {})
    .map(([o, n]) => `${n} ${o}`).join(" · ");
  const box = el("div", {});
  box.append(el("p", {class:"metaline"},
    tally || "no lines in this workbook"));
  if (!rows.length) return box;
  const head = el("tr", {},
    ...["line", "row", "outcome", "actions", ...cellKeys, "detail"]
    .map(h => el("th", {}, h)));
  const tbody = el("tbody", {});
  for (const r of rows) {
    const detail = [r.reason, ...(r.refusals || []), ...(r.notes || [])]
      .filter(Boolean).join(" · ");
    const cells = cellsByLine.get(r.line) || {};
    tbody.append(el("tr", {},
      el("td", {class:"mono"}, String(r.line)),
      el("td", {class:"mono"},
        r.self ? el("a", {href: "#" + r.self}, "created row")
               : (r.id || "")),
      el("td", {}, r.outcome || ""),
      el("td", {class:"mono"}, (r.actions || []).join(", ")),
      ...cellKeys.map(k => {
        const v = cells[k], ref = (r.refs || {})[k];
        return el("td", {class:"mono"},
          ref ? el("a", {href: "#" + ref.self, title: String(v ?? "")},
                   ref.display || String(v))
              : v == null ? "" : String(v));
      }),
      el("td", {class:"muted"}, detail)));
  }
  box.append(el("div", {style:"overflow:auto;max-height:60vh"},
    el("table", {class:"items"}, el("thead", {}, head), tbody)));
  return box;
}

/* ── attachments: the bytes ride the engine's static route
   /api/attachments/{id}/bytes (wire 10 advertises no template — a
   recorded adaptation from 9's discovery-advertised route) ─────────── */
function bytesHref(id) { return `/api/attachments/${encodeURIComponent(id)}/bytes`; }
function isViewable(mime) {
  return /^(image\/|text\/)/.test(mime || "") || mime === "application/pdf";
}
async function fetchAttachmentBlob(id) {
  try {
    const res = await fetch(bytesHref(id), {headers: principalHeaders()});
    if (!res.ok) { toast(`couldn't fetch the file — ${res.status}`); return null; }
    return await res.blob();
  } catch { toast("couldn't reach the file"); return null; }
}
async function downloadAttachment(id, name) {
  const blob = await fetchAttachmentBlob(id);
  if (!blob) return;
  const obj = URL.createObjectURL(blob);
  const a = el("a", {href: obj, download: name || "file"});
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(obj), 10000);
}
async function viewAttachment(id) {
  const blob = await fetchAttachmentBlob(id);
  if (!blob) return;
  const obj = URL.createObjectURL(blob);
  window.open(obj, "_blank");
  setTimeout(() => URL.revokeObjectURL(obj), 60000);
}
function downloadButton(item, small) {
  if (!item || item.state !== "uploaded") return null;
  const id = String(item.self).split("/").pop();
  const btn = el("button", {
    style: small ? "font-size:12px;padding:3px 8px" : "",
    title:"download this file"}, "↓ Download");
  btn.addEventListener("click", () => downloadAttachment(id, null));
  return btn;
}
function humanSize(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
function uploadButton({entry, doc, prefill, onDone, small}) {
  const btn = el("button", {class:"primary", "data-action":"create",
    style: small ? "font-size:12px;padding:3px 8px" : ""}, "Upload file");
  btn.addEventListener("click", () =>
    uploadDialog({entry, doc, prefill, onDone}));
  return btn;
}
/* the two-phase reserve→PUT-bytes flow: the create action's own href
   reserves the row; the bytes go to the static route. name and the
   media type come off the chosen File. */
async function uploadDialog({entry, doc, prefill, onDone}) {
  prefill = prefill || {};
  const props = entry.input?.properties || {};
  const mediaField = "media_type" in props ? "media_type"
                   : "mime" in props ? "mime" : null;
  const file = el("input", {type:"file", id:"f_file"});
  const preview = el("div", {class:"muted mono", style:"margin:6px 0"},
    "no file chosen");
  const errBox = el("div", {});
  const upBtn = el("button", {class:"primary", disabled:"true"}, "Upload");
  let chosen = null, reservedId = null, reservedBody = null;
  file.addEventListener("change", () => {
    chosen = file.files && file.files[0];
    if (chosen) {
      preview.textContent = `${chosen.name} · `
        + `${chosen.type || "application/octet-stream"} · `
        + `${humanSize(chosen.size)}`;
      upBtn.removeAttribute("disabled");
    } else {
      preview.textContent = "no file chosen";
      upBtn.setAttribute("disabled", "true");
    }
  });
  const extraForm = el("div", {});
  for (const [k, rawProp] of Object.entries(props)) {
    if (k === "name" || k === mediaField || k === "size" || k === "sha256") continue;
    const prop = schemaProp(rawProp);
    if (prop.const !== undefined) continue;
    extraForm.append(el("div", {class:"field"},
      el("label", {}, el("b", {}, k)),
      fieldWidget(k, rawProp, prefill[k])));
  }
  const dlg = el("dialog", {},
    el("div", {class:"dlghead"}, el("h3", {}, "Upload a file"),
      el("p", {class:"metaline"}, "reserved → uploaded · the bytes are written once")),
    el("div", {class:"dlgbody"},
      el("div", {class:"field"}, el("label", {}, el("b", {}, "File")),
        file, preview),
      extraForm, errBox),
    el("div", {class:"dlgfoot"},
      el("button", {onclick: () => { dlg.close(); dlg.remove();
                                     if (reservedId) render(); }}, "Cancel"),
      upBtn));
  upBtn.addEventListener("click", async () => {
    if (!chosen) return;
    errBox.textContent = "";
    upBtn.setAttribute("disabled", "true");
    if (!reservedId) {
      const create = {name: chosen.name};
      if (mediaField) create[mediaField] = chosen.type || "application/octet-stream";
      for (const [k, rawProp] of Object.entries(props)) {
        const prop = schemaProp(rawProp);
        if (prop.const !== undefined) create[k] = prop.const;
      }
      for (const node of extraForm.querySelectorAll("[name]"))
        if (node.value) create[node.getAttribute("name")] = node.value;
      const res = await api(entry.href, {method: entry.method || "POST",
        body: JSON.stringify(Object.assign({}, prefill, create))});
      if (!res.ok) {
        errBox.append(problemBox(res.body));
        upBtn.removeAttribute("disabled");
        return;
      }
      reservedBody = res.body;
      reservedId = String(res.body.self).split("/").pop();
    }
    try {
      const res = await fetch(bytesHref(reservedId), {method:"PUT",
        headers: {...principalHeaders(),
          "Content-Type": chosen.type || "application/octet-stream"},
        body: chosen});
      if (!res.ok) {
        const b = await res.json().catch(() => null);
        errBox.append(problemBox(b || {title:"Upload failed",
          detail:`the bytes route returned ${res.status} — the file is `
            + "reserved; try uploading again"}));
        upBtn.removeAttribute("disabled");  /* retry re-PUTs the same reserve */
        return;
      }
    } catch {
      errBox.append(problemBox({title:"Upload failed",
        detail:"could not reach the bytes route — the file is reserved; try again"}));
      upBtn.removeAttribute("disabled");
      return;
    }
    dlg.close(); dlg.remove();
    toast(`${chosen.name} uploaded ✓`);
    onDone && onDone(reservedBody);
  });
  document.body.append(dlg);
  dlg.showModal();
}

