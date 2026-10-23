'use strict';

/**
 * Git 历史浏览器 · 前端
 *
 * 纯 vanilla JS, 不引任何构建工具 —— 跟 core 一样, 打开就能跑。
 */

const $ = (id) => document.getElementById(id);

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
    el._timer = setTimeout(() => el.classList.add('hidden'), 4000);
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

// ------------------------------------------------------------------ 加载

async function openRepo() {
    const path = $('repoPath').value.trim();
    if (!path) {
        toast('请输入仓库路径', 'error');
        return;
    }
    state.repoPath = path;
    try {
        const info = await api('/api/info', { path });
        $('repoMeta').textContent = `${info.name} · ${info.currentBranch || '游离 HEAD'} · ${info.headCommitId?.slice(0, 8) || '-'}`;
        await Promise.all([loadBranches(), loadStatus()]);
        toast('已打开 ' + info.name, 'success');
    } catch (e) {
        toast('打开失败: ' + e.message, 'error');
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
        li.appendChild(el('span', 'name', b.name));
        if (b.current) li.appendChild(el('span', 'tag current', '当前'));
        else if (b.remote) li.appendChild(el('span', 'tag remote', '远端'));
        if (b.ahead > 0) li.appendChild(el('span', 'ahead', '↑' + b.ahead));
        if (b.behind > 0) li.appendChild(el('span', 'behind', '↓' + b.behind));
        li.onclick = () => selectBranch(b);
        list.appendChild(li);
    });

    const sel = $('branchSelect');
    sel.innerHTML = '';
    const head = el('option', null, 'HEAD (当前)');
    head.value = 'HEAD';
    sel.appendChild(head);
    branches.filter((b) => !b.remote).forEach((b) => {
        const opt = el('option', null, b.name);
        opt.value = b.name;
        sel.appendChild(opt);
    });
    const current = branches.find((b) => b.current);
    if (current) sel.value = current.name;
    sel.onchange = () => loadHistory(sel.value);
}

async function selectBranch(branch) {
    $('branchSelect').value = branch.remote ? branch.name : branch.name;
    await loadHistory(branch.name);
    document.querySelectorAll('.branch-item').forEach((n, i) => {
        n.classList.toggle('active', state.branches[i] === branch);
    });
}

async function loadHistory(ref) {
    try {
        const rows = await api('/api/history', { path: state.repoPath, ref: ref || 'HEAD', limit: 300 });
        state.history = rows;
        const list = $('historyList');
        list.innerHTML = '';
        $('historyCount').textContent = rows.length;

        rows.forEach((row) => {
            const c = row.commit();
            const li = el('li', 'commit-item' + (c.parents().length > 1 ? ' merge' : ''));

            const graph = el('span', 'graph');
            graph.appendChild(document.createTextNode(row.prefix()));
            const marker = el('span', 'marker', row.marker());
            graph.appendChild(marker);
            graph.appendChild(document.createTextNode(row.suffix()));
            li.appendChild(graph);

            li.appendChild(el('span', 'sha', c.shortId()));
            li.appendChild(el('span', 'subject', c.subject()));
            if (c.branchLabels().length) {
                li.appendChild(el('span', 'labels', c.branchLabels().join(', ')));
            }
            li.appendChild(el('span', 'meta', c.relativeTime()));
            li.onclick = () => selectCommit(c.id, li);
            list.appendChild(li);
        });

        if (rows.length) await selectCommit(rows[0].commit().id, list.firstChild);
        else {
            $('commitDetail').innerHTML = '<div class="empty">这个 ref 下没有提交</div>';
            $('diffPane').classList.add('hidden');
        }
    } catch (e) {
        toast('加载历史失败: ' + e.message, 'error');
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
        const h = el('h3', null, commit.subject());
        detail.appendChild(h);

        const kv = el('div', 'kv');
        kv.appendChild(el('span', null, '作者'));
        kv.appendChild(el('b', null, commit.author()));
        kv.appendChild(el('span', null, '时间'));
        kv.appendChild(el('b', null, timeAgo(commit.timestamp / 1000)));
        kv.appendChild(el('span', null, 'SHA'));
        kv.appendChild(el('b', null, commit.id));
        detail.appendChild(kv);

        if (commit.parents().length > 1) {
            const p = el('div', 'kv');
            p.style.marginTop = '4px';
            p.appendChild(el('span', null, '合并自'));
            p.appendChild(el('b', null, commit.parents().map((x) => x.slice(0, 8)).join(', ')));
            detail.appendChild(p);
        }

        const files = el('div', 'kv');
        files.style.marginTop = '4px';
        files.appendChild(el('span', null, '改动'));
        files.appendChild(el('b', null, changed.length + ' 个文件'));
        detail.appendChild(files);

        renderFileTabs(changed);
    } catch (e) {
        toast('加载提交详情失败: ' + e.message, 'error');
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
        toast('加载状态失败: ' + e.message, 'error');
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
        toast(`提交成功 ${result.shortId} · ${result.filesChanged} 个文件`, 'success');
        $('commitMessage').value = '';
        $('stageAll').checked = false;
        state.selectedFiles.clear();
        await Promise.all([loadHistory($('branchSelect').value), loadStatus()]);
    } catch (e) {
        toast('提交失败: ' + e.message, 'error');
    }
}

// ------------------------------------------------------------------ 启动

$('btnOpen').onclick = openRepo;
$('btnCommit').onclick = doCommit;
$('repoPath').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') openRepo();
});
$('commitMessage').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doCommit();
});

// 尝试用最近打开过的路径预填
fetch('/api/info?path=.').then(() => {}).catch(() => {});
