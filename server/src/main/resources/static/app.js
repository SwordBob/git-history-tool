'use strict';

/**
 * Git 历史浏览器 · 前端
 *
 * 纯 vanilla JS, 不引任何构建工具 —— 跟 core 一样, 打开就能跑。
 *
 * ⚠️ 约定: 后端返回的是 JSON, 属性就是普通字段, 一律用 `obj.field` 访问。
 *    绝不能写 `obj.field()` —— 那是 Java record 访问器的写法, 在 JS 里会直接
 *    TypeError: obj.field is not a function (之前的 "加载失败" 就是这么来的)。
 */

const $ = (id) => document.getElementById(id);

// 前端资源标识: 动态加载时 URL 上带的唯一时间戳, 只用于诊断。
const BUILD = (() => {
    const src = document.currentScript && document.currentScript.src;
    const m = src && src.match(/[?&]t=([\w.-]+)/);
    return window.__BUILD__ || (m ? m[1] : 'dev');
})();

const state = {
    repoPath: '',
    branches: [],
    history: [],
    selectedCommit: null,
    changedFiles: [],
    selectedFiles: new Set(),
    stagedPaths: new Set(),
};

// ------------------------------------------------------------------ 工具

async function api(path, params) {
    const url = new URL(path, location.origin);
    Object.entries(params || {}).forEach(([k, v]) => {
        if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, v);
    });
    const res = await fetch(url);
    const text = await res.text();
    let data;
    try {
        data = text ? JSON.parse(text) : null;
    } catch (e) {
        throw new Error('响应不是合法 JSON: ' + text.slice(0, 200));
    }
    if (!res.ok) {
        throw new Error((data && data.error) || ('HTTP ' + res.status));
    }
    return data;
}

function toast(message, kind = '') {
    const el = $('toast');
    el.textContent = message;
    el.className = 'toast ' + kind;
    clearTimeout(el._timer);
    el._timer = setTimeout(() => el.classList.add('hidden'), 6000);
}

function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
}

function timeAgo(ts) {
    if (!ts) return '';
    const diff = Date.now() / 1000 - ts;
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
    if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
    if (diff < 2592000) return Math.floor(diff / 86400) + ' 天前';
    if (diff < 31536000) return Math.floor(diff / 2592000) + ' 个月前';
    return Math.floor(diff / 31536000) + ' 年前';
}

/** 分支显示名: 本地显示 main, 远端显示 origin/main (否则两个 main 分不清) */
function branchLabel(b) {
    if (!b.remote) return b.name;
    return b.fullName.replace(/^refs\/remotes\//, '');
}

// ------------------------------------------------------------------ 加载

// 全局错误捕获: 出错时把真实原因显示在界面上,
// 不要再只甩一句"加载失败"让人猜。
window.addEventListener('error', (e) => {
    toast('页面错误[' + BUILD + ']: ' + (e.message || e.error || '未知错误'), 'error');
});
window.addEventListener('unhandledrejection', (e) => {
    const reason = e.reason && e.reason.message ? e.reason.message : e.reason;
    toast('异步错误[' + BUILD + ']: ' + reason, 'error');
});

async function openRepo() {
    const path = $('repoPath').value.trim();
    if (!path) {
        toast('请输入仓库路径', 'error');
        return;
    }
    state.repoPath = path;
    try {
        const info = await api('/api/info', { path });
        $('repoMeta').textContent =
            `${info.name} · ${info.currentBranch || '游离 HEAD'} · ${(info.headCommitId || '-').slice(0, 8)}`;
        await Promise.all([loadBranches(), loadStatus()]);
        // 分支下拉框选好之后, 主动把当前分支的历史拉出来
        await loadHistory($('branchSelect').value || 'HEAD');
        toast('已打开 ' + info.name, 'success');
    } catch (e) {
        toast('打开失败[' + BUILD + ']: ' + e.message, 'error');
    }
}

async function loadBranches() {
    const branches = await api('/api/branches', { path: state.repoPath });
    state.branches = branches;

    const list = $('branchList');
    list.innerHTML = '';
    $('branchCount').textContent = branches.length;

    branches.forEach((b) => {
        const li = el('li', 'branch-item');
        li.appendChild(el('span', 'name', branchLabel(b)));
        if (b.current) li.appendChild(el('span', 'tag current', '当前'));
        else if (b.remote) li.appendChild(el('span', 'tag remote', '远端'));
        if (b.ahead > 0) li.appendChild(el('span', 'ahead', '↑' + b.ahead));
        if (b.behind > 0) li.appendChild(el('span', 'behind', '↓' + b.behind));
        li.onclick = () => selectBranch(b, li);
        list.appendChild(li);
    });

    // 下拉框: 显示短名, 但 value 用 fullName —— 远端分支短名跟本地同名,
    // 只传短名会查错分支 (refs/heads/main vs refs/remotes/origin/main)
    const sel = $('branchSelect');
    sel.innerHTML = '';
    const head = el('option', null, 'HEAD (当前)');
    head.value = 'HEAD';
    sel.appendChild(head);
    branches.forEach((b) => {
        const opt = el('option', null, branchLabel(b));
        opt.value = b.fullName;
        sel.appendChild(opt);
    });
    const current = branches.find((b) => b.current);
    if (current) sel.value = current.fullName;
    else sel.value = 'HEAD';
    sel.onchange = () => loadHistory(sel.value);
}

async function selectBranch(branch, node) {
    $('branchSelect').value = branch.fullName;
    await loadHistory(branch.fullName);
    document.querySelectorAll('.branch-item').forEach((n) => n.classList.remove('active'));
    if (node) node.classList.add('active');
}

async function loadHistory(ref) {
    try {
        const rows = await api('/api/history', { path: state.repoPath, ref: ref || 'HEAD', limit: 300 });
        state.history = rows;
        const list = $('historyList');
        list.innerHTML = '';
        $('historyCount').textContent = rows.length;

        rows.forEach((row) => {
            const c = row.commit;                      // JSON 属性, 不是方法
            const li = el('li', 'commit-item' + (c.parents.length > 1 ? ' merge' : ''));

            const graph = el('span', 'graph');
            graph.appendChild(document.createTextNode(row.prefix));
            graph.appendChild(el('span', 'marker', row.marker));
            graph.appendChild(document.createTextNode(row.suffix));
            li.appendChild(graph);

            li.appendChild(el('span', 'sha', c.shortId));
            li.appendChild(el('span', 'subject', c.subject));
            if (c.branchLabels.length) {
                li.appendChild(el('span', 'labels', c.branchLabels.join(', ')));
            }
            li.appendChild(el('span', 'meta', c.relativeTime));
            li.onclick = () => selectCommit(c.id, li);
            list.appendChild(li);
        });

        if (rows.length) await selectCommit(rows[0].commit.id, list.firstChild);
        else {
            $('commitDetail').innerHTML = '<div class="empty">这个 ref 下没有提交</div>';
            $('diffPane').classList.add('hidden');
        }
    } catch (e) {
        toast('加载历史失败[' + BUILD + ']: ' + e.message, 'error');
    }
}

async function selectCommit(commitId, node) {
    state.selectedCommit = commitId;
    document.querySelectorAll('.commit-item').forEach((n) => n.classList.remove('active'));
    if (node) node.classList.add('active');

    try {
        const [commit, changed] = await Promise.all([
            api('/api/commit', { path: state.repoPath, id: commitId }),
            api('/api/changed', { path: state.repoPath, commit: commitId }),
        ]);
        state.changedFiles = changed;
        state.selectedFiles = new Set();

        const detail = $('commitDetail');
        detail.innerHTML = '';
        detail.appendChild(el('h3', null, commit.subject));

        const kv = el('div', 'kv');
        kv.appendChild(el('span', null, '作者'));
        kv.appendChild(el('b', null, commit.author));
        kv.appendChild(el('span', null, '时间'));
        kv.appendChild(el('b', null, timeAgo(commit.timestamp / 1000)));
        kv.appendChild(el('span', null, 'SHA'));
        kv.appendChild(el('b', null, commit.id));
        detail.appendChild(kv);

        if (commit.parents.length > 1) {
            const p = el('div', 'kv');
            p.style.marginTop = '4px';
            p.appendChild(el('span', null, '合并自'));
            p.appendChild(el('b', null, commit.parents.map((x) => x.slice(0, 8)).join(', ')));
            detail.appendChild(p);
        }

        const files = el('div', 'kv');
        files.style.marginTop = '4px';
        files.appendChild(el('span', null, '改动'));
        files.appendChild(el('b', null, changed.length + ' 个文件'));
        detail.appendChild(files);

        renderFileTabs(changed);
    } catch (e) {
        toast('加载提交详情失败[' + BUILD + ']: ' + e.message, 'error');
    }
}

function renderFileTabs(changed) {
    const tabs = $('fileTabs');
    tabs.innerHTML = '';
    $('diffPane').classList.remove('hidden');

    if (!changed.length) {
        $('diffContent').innerHTML = '<div class="diff-binary">这个提交没有文件改动</div>';
        return;
    }

    changed.forEach((d, i) => {
        const tab = el('div', 'file-tab' + (i === 0 ? ' active' : ''));
        tab.appendChild(document.createTextNode(d.path + ' '));
        if (d.additions) tab.appendChild(el('span', 'add', '+' + d.additions));
        if (d.deletions) {
            tab.appendChild(document.createTextNode(' '));
            tab.appendChild(el('span', 'del', '-' + d.deletions));
        }
        tab.onclick = () => {
            document.querySelectorAll('.file-tab').forEach((n) => n.classList.remove('active'));
            tab.classList.add('active');
            renderDiff(d);
        };
        tabs.appendChild(tab);
    });
    renderDiff(changed[0]);
}

function renderDiff(d) {
    const box = $('diffContent');
    box.innerHTML = '';
    if (d.binary) {
        box.appendChild(el('div', 'diff-binary', '二进制文件, 无法显示行级差异'));
        return;
    }
    d.lines.forEach((l) => {
        let cls = 'diff-line ';
        if (l.origin === '+') cls += 'add';
        else if (l.origin === '-') cls += 'del';
        else if (l.origin === '@') cls += 'hunk';
        else cls += 'ctx';

        const row = el('div', cls);
        row.appendChild(el('span', 'ln', l.origin === '@' ? '' : l.oldNo || ''));
        row.appendChild(el('span', 'ln', l.origin === '@' ? '' : l.newNo || ''));
        row.appendChild(el('span', 'sign', l.origin === '@' ? '' : l.origin));
        row.appendChild(el('span', 'code', l.content));
        box.appendChild(row);
    });
}

// ------------------------------------------------------------------ 工作区

async function loadStatus() {
    try {
        const status = await api('/api/status', { path: state.repoPath });
        const list = $('statusList');
        list.innerHTML = '';
        $('statusCount').textContent = status.length;
        if (!status.length) {
            list.appendChild(el('span', 'status-chip', '工作区干净'));
            return;
        }
        status.forEach((s) => {
            const chip = el('div', 'status-chip ' + s.status);
            chip.appendChild(document.createTextNode(s.path));
            if (s.staged) chip.appendChild(el('span', 'staged', '已暂存'));
            chip.title = s.status;
            chip.onclick = () => {
                if (state.selectedFiles.has(s.path)) state.selectedFiles.delete(s.path);
                else state.selectedFiles.add(s.path);
                chip.style.borderColor = state.selectedFiles.has(s.path) ? 'var(--accent)' : 'transparent';
            };
            list.appendChild(chip);
        });
    } catch (e) {
        toast('加载状态失败[' + BUILD + ']: ' + e.message, 'error');
    }
}

async function doCommit() {
    const message = $('commitMessage').value.trim();
    if (!message) {
        toast('提交信息不能为空', 'error');
        return;
    }
    const stageAll = $('stageAll').checked;
    const paths = Array.from(state.selectedFiles);
    if (!stageAll && paths.length === 0) {
        toast('请勾选要提交的文件, 或勾选"暂存全部"', 'error');
        return;
    }
    try {
        const result = await fetch('/api/commit', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: state.repoPath, message, paths, stageAll }),
        });
        const data = await result.json();
        if (!result.ok) throw new Error(data.error || ('HTTP ' + result.status));
        toast(`提交成功 ${data.shortId} · ${data.filesChanged} 个文件`, 'success');
        $('commitMessage').value = '';
        $('stageAll').checked = false;
        state.selectedFiles.clear();
        await Promise.all([loadHistory($('branchSelect').value), loadStatus()]);
    } catch (e) {
        toast('提交失败[' + BUILD + ']: ' + e.message, 'error');
    }
}

// ------------------------------------------------------------------ 启动

// 显示服务端构建时间 —— 用户能一眼看出自己跑的是不是最新那一版
fetch('/api/version')
    .then((r) => r.json())
    .then((v) => {
        const tag = $('buildTag');
        if (tag) tag.textContent = 'build ' + v.build;
    })
    .catch(() => {});

$('btnOpen').onclick = openRepo;
$('btnCommit').onclick = doCommit;
$('repoPath').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') openRepo();
});
$('commitMessage').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doCommit();
});

// URL 带上 ?path=/xxx/repo 就自动打开 (方便脚本/headless 浏览器验证)
const autoPath = new URLSearchParams(location.search).get('path');
if (autoPath) {
    $('repoPath').value = autoPath;
    openRepo();
}
