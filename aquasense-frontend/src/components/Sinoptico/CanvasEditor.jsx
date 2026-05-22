/**
 * CanvasEditor.jsx
 * Canvas SVG con pan + zoom, paleta lateral de 40+ componentes,
 * conexiones como líneas SVG con flecha, integración con API de tuberías.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import api from '../../services/api';
import { PALETA_MAP, getPaletaGrupos } from './paletaItems.jsx';
import { getShapeDef, getShapeSize, renderShape } from './equipShapes.jsx';
import { useLanguage } from '../../context/LanguageContext.jsx';
import { useRole } from '../../hooks/useRole.js';
import s from './CanvasEditor.module.css';

// ── Constantes ────────────────────────────────────────────────────────────────
const COMP_W = 80;
const COMP_H = 72;
const PORT_R = 6;
const MIN_ZOOM = 0.25;
const MAX_ZOOM = 3;
const ZOOM_STEP = 0.12;

const PIPE_TYPES = {
  aguaCruda:           { label: 'Água Bruta',        color: '#00d4ff', width: 4, dasharray: 'none' },
  aguaTratada:         { label: 'Água Tratada',       color: '#00e87a', width: 4, dasharray: 'none' },
  dosificacionQuimica: { label: 'Dosificação Química',color: '#f5a623', width: 2, dasharray: '6 3'  },
  purgaLodo:           { label: 'Purga de Lodo',      color: '#8aaec8', width: 3, dasharray: 'none' },
  retorno:             { label: 'Retorno',             color: '#8aaec8', width: 2, dasharray: '12 4' },
  venteo:              { label: 'Venteo',              color: '#ffffff', width: 1, dasharray: '2 4'  },
};
const PIPE_TYPE_KEYS = Object.keys(PIPE_TYPES);

const LEGACY_STYLE_MAP = {
  solid:  'aguaCruda',
  dashed: 'dosificacionQuimica',
  dotted: 'retorno',
};

function resolvePipeType(conn) {
  if (conn.pipeType && PIPE_TYPES[conn.pipeType]) return conn.pipeType;
  if (conn.style && LEGACY_STYLE_MAP[conn.style]) return LEGACY_STYLE_MAP[conn.style];
  return 'aguaCruda';
}

const STATUS_COLOR = {
  ok:   '#00e87a',
  warn: '#f5a623',
  err:  '#ff3d5a',
  none: '#00c8e8',
};

function uid() {
  return `inst_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
}

function portOffset(componenteId, port) {
  const shape = getShapeDef(componenteId);
  if (shape) {
    const p = shape.ports.find(pt => pt.id === port);
    if (p) return { dx: p.x, dy: p.y };
    const { width: W, height: H } = shape;
    switch (port) {
      case 'left':   return { dx: 0,     dy: H / 2 };
      case 'right':  return { dx: W,     dy: H / 2 };
      case 'top':    return { dx: W / 2, dy: 0 };
      case 'bottom': return { dx: W / 2, dy: H };
      default:       return { dx: W / 2, dy: H / 2 };
    }
  }
  switch (port) {
    case 'left':   return { dx: 0,         dy: COMP_H / 2 };
    case 'right':  return { dx: COMP_W,    dy: COMP_H / 2 };
    case 'top':    return { dx: COMP_W / 2, dy: 0 };
    case 'bottom': return { dx: COMP_W / 2, dy: COMP_H };
    default:       return { dx: 0, dy: 0 };
  }
}

function portCenter(inst, port) {
  const { dx, dy } = portOffset(inst.componenteId, port);
  return { x: inst.x + dx, y: inst.y + dy };
}

function getAlertState(componenteId, alertas) {
  const mine = alertas.filter(a => a.componente === componenteId && a.ativa);
  if (!mine.length) return 'none';
  if (mine.some(a => a.nivel === 'CRITICA')) return 'err';
  return 'warn';
}

function PaletteIcon({ componenteId, color }) {
  const item = PALETA_MAP[componenteId];
  if (!item) {
    const initial = (componenteId || '?')[0].toUpperCase();
    return (
      <svg viewBox="0 0 48 48" width="28" height="28" style={{ display: 'block' }}>
        <rect x="4" y="4" width="40" height="40" rx="4" fill="none" stroke={color} strokeWidth="2" />
        <text x="24" y="30" textAnchor="middle" fontSize="18" fontWeight="bold" fill={color} fontFamily="monospace">
          {initial}
        </text>
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 48 48" width="28" height="28" style={{ display: 'block' }}>
      {item.renderIcon(color, 2)}
    </svg>
  );
}

function PropsPanel({ inst, onLabelChange, onDelete, onClose }) {
  const { t } = useLanguage();
  const [label, setLabel] = useState(inst.label);

  useEffect(() => {
    setLabel(inst.label);
  }, [inst.id, inst.label]);

  function commit() {
    onLabelChange(inst.id, label);
  }

  return (
    <div className={s.propsPanel}>
      <div className={s.propsPanelHeader}>
        <span className={s.propsPanelTitle}>{t('props_title')}</span>
        <button className={s.propsPanelClose} onClick={onClose}>×</button>
      </div>
      <div className={s.propsRow}>
        <span className={s.propsKey}>ID</span>
        <span className={s.propsVal}>{inst.componenteId}</span>
      </div>
      <div className={s.propsRow}>
        <span className={s.propsKey}>X / Y</span>
        <span className={s.propsVal}>{Math.round(inst.x)} / {Math.round(inst.y)}</span>
      </div>
      <div className={s.propsRow}>
        <span className={s.propsKey}>LABEL</span>
        <input
          className={s.propsInput}
          value={label}
          onChange={e => setLabel(e.target.value)}
          onBlur={commit}
          onKeyDown={e => e.key === 'Enter' && commit()}
        />
      </div>
      <button className={s.propsDelete} onClick={() => onDelete(inst.id)}>
        {t('props_delete')}
      </button>
    </div>
  );
}

// ── Componente principal ──────────────────────────────────────────────────────
export default function CanvasEditor({
  projectId,
  initialLayout,
  estado,
  alertas = [],
  onSave,
  onCancel,
}) {
  const { t } = useLanguage();
  const paletaGrupos = getPaletaGrupos(t);
  const { isAdmin, isOperador } = useRole(projectId);
  const canClear = isAdmin || isOperador;

  const [showClearModal, setShowClearModal] = useState(false);

  // ── Estado del canvas ────────────────────────────────────────────────────────
  const [instances, setInstances] = useState(() => {
    if (initialLayout?.componentes) return initialLayout.componentes;
    if (initialLayout?.cards) {
      return Object.entries(initialLayout.cards).map(([instId, c]) => ({
        id: instId,
        componenteId: c.type,
        x: c.x,
        y: c.y,
        label: PALETA_MAP[c.type]?.label ?? c.type,
      }));
    }
    return [];
  });

  const [connections, setConnections] = useState(() => {
    if (initialLayout?.tuberias) {
      return initialLayout.tuberias.map(conn => ({
        ...conn,
        pipeType: resolvePipeType(conn),
      }));
    }
    if (initialLayout?.connections) {
      return initialLayout.connections.map((conn, i) => ({
        id: `conn_${i}`,
        fromInstanceId: conn.from?.cardId,
        toInstanceId: conn.to?.cardId,
        fromPort: conn.from?.port ?? 'right',
        toPort: conn.to?.port ?? 'left',
        pipeType: resolvePipeType(conn),
      }));
    }
    return [];
  });

  const [zoom, setZoom] = useState(initialLayout?.zoom ?? 1);
  const [pan, setPan] = useState({ x: initialLayout?.panX ?? 0, y: initialLayout?.panY ?? 0 });

  // ── Interacción ──────────────────────────────────────────────────────────────
  const [selectedId, setSelectedId] = useState(null);
  const [pendingFrom, setPendingFrom] = useState(null);
  const [mouseCanvas, setMouseCanvas] = useState({ x: 0, y: 0 });
  const [pipeType, setPipeType] = useState('aguaCruda');
  const [openGroups, setOpenGroups] = useState(() => {
    const init = {};
    getPaletaGrupos(k => k).forEach(g => { init[g.id] = true; });
    return init;
  });
  const [saving, setSaving] = useState(false);

  // ── Sidebar colapsable ──────────────────────────────────────────────────────
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => localStorage.getItem('ce_sb_collapsed') === '1'
  );
  const [sidebarWidth, setSidebarWidth] = useState(
    () => parseInt(localStorage.getItem('ce_sb_width') || '220', 10)
  );
  const resizerActiveRef = useRef(false);
  const resizerStartRef = useRef({ x: 0, w: 0 });

  // ── Space pan ──────────────────────────────────────────────────────────────
  const spaceRef = useRef(false);
  const [spaceDown, setSpaceDown] = useState(false);

  // ── Print mode ─────────────────────────────────────────────────────────────
  const [printMode, setPrintMode] = useState(false);
  const [printRectDraw, setPrintRectDraw] = useState(null);
  const printRectRef = useRef(null);
  const zoomRef = useRef(zoom);
  const panRef = useRef(pan);

  // Refs para drag
  const svgRef = useRef(null);
  const panningRef = useRef(null);
  const draggingRef = useRef(null);

  // ── Sync refs ───────────────────────────────────────────────────────────────
  useEffect(() => { zoomRef.current = zoom; }, [zoom]);
  useEffect(() => { panRef.current = pan; }, [pan]);

  // ── Helpers de coordenadas ──────────────────────────────────────────────────
  function clientToCanvas(clientX, clientY) {
    const rect = svgRef.current.getBoundingClientRect();
    return {
      x: (clientX - rect.left - pan.x) / zoom,
      y: (clientY - rect.top - pan.y) / zoom,
    };
  }

  // ── Zoom (scroll) ───────────────────────────────────────────────────────────
  const handleWheel = useCallback((e) => {
    e.preventDefault();
    const rect = svgRef.current.getBoundingClientRect();
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;

    setZoom(prev => {
      const delta = e.deltaY < 0 ? ZOOM_STEP : -ZOOM_STEP;
      const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, prev + delta));
      const scale = next / prev;
      setPan(p => ({
        x: mouseX - scale * (mouseX - p.x),
        y: mouseY - scale * (mouseY - p.y),
      }));
      return next;
    });
  }, []);

  useEffect(() => {
    const el = svgRef.current;
    if (!el) return;
    el.addEventListener('wheel', handleWheel, { passive: false });
    return () => el.removeEventListener('wheel', handleWheel);
  }, [handleWheel]);

  // ── Teclado: ESC + Space ───────────────────────────────────────────────────
  useEffect(() => {
    function onKeyDown(e) {
      const active = document.activeElement;
      const inInput = active?.tagName === 'INPUT' || active?.tagName === 'TEXTAREA';
      if (e.key === 'Escape') {
        setPendingFrom(null);
        setPrintMode(false);
        setPrintRectDraw(null);
        printRectRef.current = null;
      }
      if (e.key === ' ' && !inInput) {
        e.preventDefault();
        spaceRef.current = true;
        setSpaceDown(true);
      }
    }
    function onKeyUp(e) {
      if (e.key === ' ') {
        spaceRef.current = false;
        setSpaceDown(false);
        panningRef.current = null;
      }
    }
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
    };
  }, []);

  // ── Sidebar resize (global mouse) ──────────────────────────────────────────
  useEffect(() => {
    function onMouseMove(e) {
      if (!resizerActiveRef.current) return;
      const dx = e.clientX - resizerStartRef.current.x;
      const newW = Math.max(200, Math.min(480, resizerStartRef.current.w + dx));
      setSidebarWidth(newW);
      localStorage.setItem('ce_sb_width', String(newW));
    }
    function onMouseUp() {
      if (resizerActiveRef.current) {
        resizerActiveRef.current = false;
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
      }
    }
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
    };
  }, []);

  // ── Print: viewBox via beforeprint/afterprint ──────────────────────────────
  useEffect(() => {
    function onBeforePrint() {
      const r = printRectRef.current;
      if (!r || !svgRef.current) return;
      const z = zoomRef.current;
      const p = panRef.current;
      const x = Math.min(r.x1, r.x2) * z + p.x;
      const y = Math.min(r.y1, r.y2) * z + p.y;
      const w = Math.abs(r.x2 - r.x1) * z;
      const h = Math.abs(r.y2 - r.y1) * z;
      if (w > 5 && h > 5) svgRef.current.setAttribute('viewBox', `${x} ${y} ${w} ${h}`);
    }
    function onAfterPrint() {
      if (svgRef.current) svgRef.current.removeAttribute('viewBox');
      printRectRef.current = null;
    }
    window.addEventListener('beforeprint', onBeforePrint);
    window.addEventListener('afterprint', onAfterPrint);
    return () => {
      window.removeEventListener('beforeprint', onBeforePrint);
      window.removeEventListener('afterprint', onAfterPrint);
    };
  }, []);

  // ── Pan (arrastrar fondo) ────────────────────────────────────────────────────
  function handleSvgMouseDown(e) {
    // Print mode: iniciar rectángulo de selección
    if (printMode) {
      const cc = clientToCanvas(e.clientX, e.clientY);
      printRectRef.current = { x1: cc.x, y1: cc.y, x2: cc.x, y2: cc.y };
      setPrintRectDraw({ x1: cc.x, y1: cc.y, x2: cc.x, y2: cc.y });
      return;
    }
    // Space held: pan desde cualquier punto
    if (spaceRef.current) {
      setSelectedId(null);
      panningRef.current = { startX: e.clientX, startY: e.clientY, panX: pan.x, panY: pan.y };
      return;
    }
    if (e.target !== svgRef.current && e.target.dataset?.role !== 'background') return;
    if (pendingFrom) { setPendingFrom(null); return; }
    setSelectedId(null);
    panningRef.current = { startX: e.clientX, startY: e.clientY, panX: pan.x, panY: pan.y };
  }

  function handleSvgMouseMove(e) {
    if (svgRef.current) {
      setMouseCanvas(clientToCanvas(e.clientX, e.clientY));
    }

    // Print mode: actualizar rectángulo
    if (printMode && printRectRef.current) {
      const cc = clientToCanvas(e.clientX, e.clientY);
      const updated = { ...printRectRef.current, x2: cc.x, y2: cc.y };
      printRectRef.current = updated;
      setPrintRectDraw({ ...updated });
      return;
    }

    if (panningRef.current) {
      const dx = e.clientX - panningRef.current.startX;
      const dy = e.clientY - panningRef.current.startY;
      setPan({ x: panningRef.current.panX + dx, y: panningRef.current.panY + dy });
      return;
    }

    if (draggingRef.current) {
      const { instId, offsetX, offsetY } = draggingRef.current;
      const canvas = clientToCanvas(e.clientX, e.clientY);
      const x = Math.round((canvas.x - offsetX) / 16) * 16;
      const y = Math.round((canvas.y - offsetY) / 16) * 16;
      setInstances(prev =>
        prev.map(inst => inst.id === instId ? { ...inst, x, y } : inst)
      );
    }
  }

  function handleSvgMouseUp() {
    // Print mode: terminar y imprimir
    if (printMode && printRectRef.current) {
      const r = printRectRef.current;
      const w = Math.abs(r.x2 - r.x1);
      const h = Math.abs(r.y2 - r.y1);
      setPrintMode(false);
      setPrintRectDraw(null);
      if (w > 10 && h > 10) {
        window.print();
      } else {
        printRectRef.current = null;
      }
      return;
    }
    panningRef.current = null;
    draggingRef.current = null;
  }

  // ── Drag de instancias ya en el canvas ────────────────────────────────────────
  function handleInstMouseDown(e, inst) {
    e.stopPropagation();
    if (printMode) return;
    if (spaceRef.current) return; // space overrides — el SVG gestiona el pan
    if (pendingFrom) return;
    const canvas = clientToCanvas(e.clientX, e.clientY);
    draggingRef.current = {
      instId: inst.id,
      offsetX: canvas.x - inst.x,
      offsetY: canvas.y - inst.y,
    };
    setSelectedId(inst.id);
  }

  // ── Drop de la paleta ──────────────────────────────────────────────────────
  function handleSvgDragOver(e) { e.preventDefault(); }

  function handleSvgDrop(e) {
    e.preventDefault();
    const componenteId = e.dataTransfer.getData('componenteId');
    if (!componenteId) return;
    const canvas = clientToCanvas(e.clientX, e.clientY);
    const x = Math.round((canvas.x - COMP_W / 2) / 16) * 16;
    const y = Math.round((canvas.y - COMP_H / 2) / 16) * 16;
    const item = PALETA_MAP[componenteId];
    const newInst = {
      id: uid(),
      componenteId,
      x,
      y,
      label: item?.labelKey ? t(item.labelKey) : (item?.label ?? componenteId),
    };
    setInstances(prev => [...prev, newInst]);
    setSelectedId(newInst.id);
  }

  // ── Puertos y conexiones ───────────────────────────────────────────────────
  function handlePortClick(e, inst, port) {
    e.stopPropagation();
    if (!pendingFrom) {
      setPendingFrom({ instanceId: inst.id, port });
    } else {
      if (pendingFrom.instanceId === inst.id && pendingFrom.port === port) {
        setPendingFrom(null);
        return;
      }
      const newConn = {
        id: uid(),
        fromInstanceId: pendingFrom.instanceId,
        toInstanceId: inst.id,
        fromPort: pendingFrom.port,
        toPort: port,
        pipeType,
      };
      setConnections(prev => [...prev, newConn]);
      setPendingFrom(null);

      const fromInst = instances.find(i => i.id === pendingFrom.instanceId);
      if (fromInst) {
        api.post(`/api/proyectos/${projectId}/tuberias`, {
          fromComponenteId: fromInst.componenteId,
          toComponenteId: inst.componenteId,
        }).then(res => {
          const tuberiaId = res.data?.id;
          if (tuberiaId) {
            setConnections(prev =>
              prev.map(c => c.id === newConn.id ? { ...c, tuberiaId } : c)
            );
          }
        }).catch(() => {});
      }
    }
  }

  function handleDeleteConnection(connId) {
    const conn = connections.find(c => c.id === connId);
    if (conn?.tuberiaId) {
      api.delete(`/api/proyectos/${projectId}/tuberias/${conn.tuberiaId}`).catch(() => {});
    }
    setConnections(prev => prev.filter(c => c.id !== connId));
  }

  function handleDeleteInst(instId) {
    setInstances(prev => prev.filter(i => i.id !== instId));
    setConnections(prev => prev.filter(
      c => c.fromInstanceId !== instId && c.toInstanceId !== instId
    ));
    setSelectedId(null);
  }

  function handleLabelChange(instId, newLabel) {
    setInstances(prev => prev.map(i => i.id === instId ? { ...i, label: newLabel } : i));
  }

  // ── Limpar sinóptico ────────────────────────────────────────────────────────
  async function handleClearLayout() {
    setShowClearModal(false);
    setSaving(true);
    try {
      const empty = { componentes: [], tuberias: [], zoom: 1, panX: 0, panY: 0 };
      await api.post(`/api/proyectos/${projectId}/layout`, empty);
      setInstances([]);
      setConnections([]);
      setZoom(1);
      setPan({ x: 0, y: 0 });
      onSave(empty);
    } finally {
      setSaving(false);
    }
  }

  // ── Guardar ─────────────────────────────────────────────────────────────────
  async function handleSave() {
    setSaving(true);
    try {
      const payload = {
        componentes: instances,
        tuberias: connections,
        zoom,
        panX: pan.x,
        panY: pan.y,
      };
      await api.post(`/api/proyectos/${projectId}/layout`, payload);
      onSave(payload);
    } finally {
      setSaving(false);
    }
  }

  // ── Sidebar helpers ─────────────────────────────────────────────────────────
  function toggleSidebar() {
    const next = !sidebarCollapsed;
    setSidebarCollapsed(next);
    localStorage.setItem('ce_sb_collapsed', next ? '1' : '0');
  }

  function handleResizerMouseDown(e) {
    e.preventDefault();
    resizerActiveRef.current = true;
    resizerStartRef.current = { x: e.clientX, w: sidebarWidth };
    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
  }

  // ── Renderización ─────────────────────────────────────────────────────────────
  const GENERIC_PORTS = ['left', 'right', 'top', 'bottom'];

  function renderInstances() {
    return instances.map(inst => {
      const alertState = getAlertState(inst.componenteId, alertas);
      const color = STATUS_COLOR[alertState];
      const isSelected = selectedId === inst.id;
      const isPendingFrom = pendingFrom?.instanceId === inst.id;
      const shapeDef = getShapeDef(inst.componenteId);
      const { width: shW, height: shH } = getShapeSize(inst.componenteId);

      const ports = shapeDef
        ? shapeDef.ports
        : GENERIC_PORTS.map(p => {
            const { dx, dy } = portOffset(inst.componenteId, p);
            return { id: p, x: dx, y: dy };
          });

      const outlineColor = isSelected ? '#00c8e8' : (isPendingFrom ? '#f5a623' : color);

      return (
        <g
          key={inst.id}
          transform={`translate(${inst.x},${inst.y})`}
          className={s.instGroup}
          onMouseDown={e => handleInstMouseDown(e, inst)}
          style={{ cursor: printMode ? 'crosshair' : 'grab' }}
        >
          {shapeDef ? (
            <>
              <rect
                x={-6} y={-6}
                width={shW + 12} height={shH + 12}
                rx="6"
                fill="rgba(11,18,32,0.0)"
                stroke={outlineColor}
                strokeWidth={isSelected || isPendingFrom ? 1.5 : 0}
                strokeDasharray={isSelected ? '5 3' : 'none'}
                pointerEvents="none"
              />
              <g opacity="1">
                {renderShape(inst.componenteId, color, 2)}
              </g>
              {/* Punto 4: label ≥ 13px en SVG user-space */}
              <text
                x={shW / 2} y={shH + 16}
                textAnchor="middle"
                fill={color}
                fontSize="13"
                fontWeight="700"
                fontFamily="'JetBrains Mono', monospace"
                letterSpacing="0.5"
              >
                {inst.label}
              </text>
            </>
          ) : (
            <>
              {/* Contorno de selección/pendiente — invisible por defecto, mismo patrón que el canónico */}
              <rect
                x={-6} y={-6}
                width={shW + 12} height={shH + 12}
                rx="6"
                fill="rgba(11,18,32,0.0)"
                stroke={outlineColor}
                strokeWidth={isSelected || isPendingFrom ? 1.5 : 0}
                strokeDasharray={isSelected ? '5 3' : 'none'}
                pointerEvents="none"
              />
              {/* Icono inline — escalado desde viewbox 48×48 de la paleta a shW×shH */}
              {(() => {
                const sx = shW / 48;
                const sy = shH / 48;
                const compensatedSw = 2 / Math.min(sx, sy);
                const item = PALETA_MAP[inst.componenteId];
                return (
                  <g transform={`scale(${sx},${sy})`}>
                    {item
                      ? item.renderIcon(color, compensatedSw)
                      : <>
                          <rect x="4" y="4" width="40" height="40" rx="4" fill="none" stroke={color} strokeWidth={compensatedSw} />
                          <text x="24" y="30" textAnchor="middle" fontSize={18 / Math.min(sx, sy)} fontWeight="bold" fill={color} fontFamily="monospace">
                            {(inst.componenteId || '?')[0].toUpperCase()}
                          </text>
                        </>
                    }
                  </g>
                );
              })()}
              <text
                x={shW / 2} y={shH + 16}
                textAnchor="middle"
                fill={color}
                fontSize="13"
                fontWeight="700"
                fontFamily="'JetBrains Mono', monospace"
                letterSpacing="0.5"
              >
                {inst.label}
              </text>
            </>
          )}

          {ports.map(port => {
            const isActive = pendingFrom?.instanceId === inst.id && pendingFrom?.port === port.id;
            return (
              <circle
                key={port.id}
                cx={port.x} cy={port.y}
                r={PORT_R}
                fill={isActive ? '#00c8e8' : 'rgba(11,18,32,0.85)'}
                stroke="#00c8e8"
                strokeWidth="1.5"
                className={s.port}
                onClick={e => handlePortClick(e, inst, port.id)}
                style={{ cursor: 'crosshair' }}
              />
            );
          })}
        </g>
      );
    });
  }

  function renderConnections() {
    return connections.map(conn => {
      const fromInst = instances.find(i => i.id === conn.fromInstanceId);
      const toInst = instances.find(i => i.id === conn.toInstanceId);
      if (!fromInst || !toInst) return null;
      const pt = resolvePipeType(conn);
      const cfg = PIPE_TYPES[pt];
      const p1 = portCenter(fromInst, conn.fromPort ?? 'right');
      const p2 = portCenter(toInst, conn.toPort ?? 'left');
      const cx = (p1.x + p2.x) / 2;
      const d = `M ${p1.x} ${p1.y} C ${cx} ${p1.y}, ${cx} ${p2.y}, ${p2.x} ${p2.y}`;
      const markerId = `arrow_${pt}`;
      return (
        <g key={conn.id}>
          <path
            d={d}
            fill="none"
            stroke={cfg.color}
            strokeWidth={cfg.width + 3}
            strokeDasharray={cfg.dasharray === 'none' ? undefined : cfg.dasharray}
            opacity="0.12"
            pointerEvents="none"
          />
          <path
            d={d}
            fill="none"
            stroke={cfg.color}
            strokeWidth={cfg.width}
            strokeDasharray={cfg.dasharray === 'none' ? undefined : cfg.dasharray}
            markerEnd={`url(#${markerId})`}
            className={s.pipePath}
            onDoubleClick={() => handleDeleteConnection(conn.id)}
            style={{ cursor: 'pointer' }}
          />
        </g>
      );
    });
  }

  function renderGhostLine() {
    if (!pendingFrom) return null;
    const fromInst = instances.find(i => i.id === pendingFrom.instanceId);
    if (!fromInst) return null;
    const p1 = portCenter(fromInst, pendingFrom.port);
    const ghostColor = PIPE_TYPES[pipeType]?.color ?? '#00d4ff';
    return (
      <line
        x1={p1.x} y1={p1.y}
        x2={mouseCanvas.x} y2={mouseCanvas.y}
        stroke={ghostColor}
        strokeWidth="1.5"
        strokeDasharray="4 3"
        opacity="0.7"
        pointerEvents="none"
      />
    );
  }

  function renderPrintRect() {
    if (!printMode || !printRectDraw) return null;
    const { x1, y1, x2, y2 } = printRectDraw;
    return (
      <rect
        x={Math.min(x1, x2)}
        y={Math.min(y1, y2)}
        width={Math.abs(x2 - x1)}
        height={Math.abs(y2 - y1)}
        fill="none"
        stroke="#00c8e8"
        strokeWidth={1.5 / zoom}
        strokeDasharray={`${6 / zoom} ${4 / zoom}`}
        pointerEvents="none"
      />
    );
  }

  const selectedInst = instances.find(i => i.id === selectedId);

  // Cursor del SVG: crosshair en printMode, grab/grabbing con space, default normal
  const svgCursor = printMode
    ? 'crosshair'
    : spaceDown
    ? 'grab'
    : 'default';

  return (
    <div className={s.root}>
      {/* ── Toolbar ── */}
      <div className={s.toolbar}>
        <span className={s.toolbarTitle}>{t('editor_title')}</span>

        <div className={s.pipeControls}>
          <span className={s.toolbarLabel}>{t('editor_tube_label')}</span>
          <select
            className={s.pipeTypeSelect}
            value={pipeType}
            onChange={e => setPipeType(e.target.value)}
          >
            {PIPE_TYPE_KEYS.map(pt => (
              <option key={pt} value={pt}>{t('pipe_' + pt)}</option>
            ))}
          </select>
          <svg width="48" height="12" style={{ flexShrink: 0 }}>
            <line
              x1="2" y1="6" x2="46" y2="6"
              stroke={PIPE_TYPES[pipeType]?.color ?? '#00d4ff'}
              strokeWidth={Math.max(1, PIPE_TYPES[pipeType]?.width ?? 2)}
              strokeDasharray={
                PIPE_TYPES[pipeType]?.dasharray === 'none'
                  ? undefined
                  : PIPE_TYPES[pipeType]?.dasharray
              }
            />
          </svg>
        </div>

        <div className={s.zoomControls}>
          <button className={s.zoomBtn} onClick={() => setZoom(z => Math.max(MIN_ZOOM, z - ZOOM_STEP))}>−</button>
          <span className={s.zoomLabel}>{Math.round(zoom * 100)}%</span>
          <button className={s.zoomBtn} onClick={() => setZoom(z => Math.min(MAX_ZOOM, z + ZOOM_STEP))}>+</button>
          <button className={s.zoomBtn} onClick={() => { setZoom(1); setPan({ x: 0, y: 0 }); }} title="Reset view">⌂</button>
        </div>

        <div className={s.toolbarActions}>
          {/* Punto 6: print con área delimitada */}
          <button
            className={printMode ? s.btnPrintActive : s.btnPrint}
            onClick={() => {
              const next = !printMode;
              setPrintMode(next);
              if (!next) { setPrintRectDraw(null); printRectRef.current = null; }
            }}
          >
            {printMode ? t('editor_cancel_print') : t('editor_print')}
          </button>
          {canClear && (
            <button className={s.btnClear} onClick={() => setShowClearModal(true)} disabled={saving}>
              {t('clear_layout_btn')}
            </button>
          )}
          <button className={s.btnCancel} onClick={onCancel}>{t('cancel')}</button>
          <button className={s.btnSave} onClick={handleSave} disabled={saving}>
            {saving ? t('editor_saving') : t('editor_save')}
          </button>
        </div>
      </div>

      <div className={s.body}>
        {/* ── Paleta lateral: colapsable + redimensionable (Punto 3) ── */}
        <div
          className={s.palette}
          style={{ width: sidebarCollapsed ? 48 : sidebarWidth }}
        >
          <button className={s.collapseBtn} onClick={toggleSidebar} title={sidebarCollapsed ? t('editor_expand') : t('editor_collapse')}>
            {sidebarCollapsed ? '›' : '‹'}
          </button>

          {!sidebarCollapsed && (
            <>
              <div className={s.paletteTitle}>{t('editor_components_title')}</div>
              {paletaGrupos.map(grupo => (
                <div key={grupo.id} className={s.paletteGroup}>
                  <button
                    className={s.paletteGroupHeader}
                    onClick={() => setOpenGroups(prev => ({ ...prev, [grupo.id]: !prev[grupo.id] }))}
                  >
                    <span>{grupo.label}</span>
                    <span className={s.paletteGroupChevron}>{openGroups[grupo.id] ? '▾' : '▸'}</span>
                  </button>
                  {openGroups[grupo.id] && (
                    <div className={s.paletteItems}>
                      {grupo.items.map(item => (
                        <div
                          key={item.id}
                          className={s.paletteItem}
                          draggable
                          onDragStart={e => e.dataTransfer.setData('componenteId', item.id)}
                          title={item.id}
                        >
                          <PaletteIcon componenteId={item.id} color="#00c8e8" />
                          <span className={s.paletteItemLabel}>{item.label}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </>
          )}

          {/* Resize handle — solo cuando está expandida */}
          {!sidebarCollapsed && (
            <div className={s.resizeHandle} onMouseDown={handleResizerMouseDown} />
          )}
        </div>

        {/* ── Canvas SVG ── */}
        <div className={s.canvasWrap}>
          <svg
            ref={svgRef}
            className={s.canvas}
            style={{ cursor: svgCursor }}
            onMouseDown={handleSvgMouseDown}
            onMouseMove={handleSvgMouseMove}
            onMouseUp={handleSvgMouseUp}
            onMouseLeave={handleSvgMouseUp}
            onDragOver={handleSvgDragOver}
            onDrop={handleSvgDrop}
          >
            <defs>
              {PIPE_TYPE_KEYS.map(pt => (
                <marker key={pt} id={`arrow_${pt}`} markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                  <path d="M0,0 L8,4 L0,8 Z" fill={PIPE_TYPES[pt].color} opacity="0.85" />
                </marker>
              ))}
              <pattern id="grid16" width="16" height="16" patternUnits="userSpaceOnUse"
                patternTransform={`translate(${pan.x % 16},${pan.y % 16}) scale(${zoom})`}>
                <path d="M 16 0 L 0 0 0 16" fill="none" stroke="#1a2840" strokeWidth="0.5" />
              </pattern>
              <pattern id="grid80" width="80" height="80" patternUnits="userSpaceOnUse"
                patternTransform={`translate(${pan.x % 80},${pan.y % 80}) scale(${zoom})`}>
                <rect width="80" height="80" fill="url(#grid16)" />
                <path d="M 80 0 L 0 0 0 80" fill="none" stroke="#223354" strokeWidth="1" />
              </pattern>
            </defs>

            <rect
              width="100%" height="100%"
              fill="url(#grid80)"
              data-role="background"
            />

            <g transform={`translate(${pan.x},${pan.y}) scale(${zoom})`}>
              {renderConnections()}
              {renderGhostLine()}
              {renderInstances()}
              {/* Punto 6: rectángulo de impresión */}
              {renderPrintRect()}
            </g>
          </svg>

          {instances.length === 0 && (
            <div className={s.emptyHint}>
              {t('canvas_drop_hint')}
            </div>
          )}

          {selectedInst && (
            <PropsPanel
              inst={selectedInst}
              onLabelChange={handleLabelChange}
              onDelete={handleDeleteInst}
              onClose={() => setSelectedId(null)}
            />
          )}

          {/* Hints — conexión y print */}
          {pendingFrom && (
            <div className={s.connectHint}>
              {t('connect_hint')}
            </div>
          )}
          {printMode && !printRectDraw && (
            <div className={s.connectHint}>
              {t('print_hint')}
            </div>
          )}
        </div>
      </div>

      {/* Modal de confirmación — limpiar sinóptico */}
      {showClearModal && (
        <div className={s.modalOverlay}>
          <div className={s.modal}>
            <p className={s.modalTitle}>{t('clear_layout_btn')}</p>
            <p className={s.modalText}>{t('clear_layout_confirm')}</p>
            <div className={s.modalActions}>
              <button className={s.btnCancel} onClick={() => setShowClearModal(false)}>{t('cancel')}</button>
              <button className={s.btnDanger} onClick={handleClearLayout}>{t('clear_layout_btn')}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
