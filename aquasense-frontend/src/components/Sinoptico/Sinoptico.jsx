import { useEffect, useState, useCallback, memo } from 'react';
import api from '../../services/api';
import { useLanguage } from '../../context/LanguageContext';
import CanvasEditor from './CanvasEditor';
import { getShapeDef, getShapeSize, renderShape } from './equipShapes.jsx';
import { PALETA_MAP } from './paletaItems.jsx';
import ModoPanel from '../ModoPanel/ModoPanel';
import s from './Sinoptico.module.css';

// ── Datos de telemetría por componenteId ─────────────────────────────────────
const PRIMARY = {
  bomba_captacao:     { key: 'caudal',            unit: 'm³/h' },
  reja_tamiz:         { key: 'diferencialPresion', unit: 'mbar' },
  coagulacion:        { key: 'phPostCoagulacion',  unit: 'pH'   },
  decantador:         { key: 'turbidezSalida',     unit: 'NTU'  },
  filtracion:         { key: 'turbidezSalida',     unit: 'NTU'  },
  desinfeccion:       { key: 'cloroResidual',      unit: 'mg/L' },
  reservorio:         { key: 'nivel',              unit: '%'    },
  bomba_distribucion: { key: 'caudal',             unit: 'm³/h' },
};

// ── Helpers ───────────────────────────────────────────────────────────────────
function getAlertState(type, alertas) {
  const mine = alertas.filter(a => a.componente === type && a.ativa);
  if (!mine.length) return 'ok';
  if (mine.some(a => a.nivel === 'CRITICA')) return 'err';
  return 'warn';
}

const STATE_STROKE = { ok: '#00e87a', warn: '#f5a623', err: '#ff3d5a' };

// Sistema de tipos de tubería — espejo de CanvasEditor (sin importar para evitar acoplamiento)
const PIPE_TYPES = {
  aguaCruda:           { color: '#00d4ff', width: 4, dasharray: 'none' },
  aguaTratada:         { color: '#00e87a', width: 4, dasharray: 'none' },
  dosificacionQuimica: { color: '#f5a623', width: 2, dasharray: '6 3'  },
  purgaLodo:           { color: '#8aaec8', width: 3, dasharray: 'none' },
  retorno:             { color: '#8aaec8', width: 2, dasharray: '12 4' },
  venteo:              { color: '#ffffff', width: 1, dasharray: '2 4'  },
};
const PIPE_TYPE_KEYS = Object.keys(PIPE_TYPES);

// Migración legacy: style antiguo → pipeType
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

function parseLayout(raw) {
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!parsed) return { cards: {}, connections: [] };
    // Nuevo formato (CanvasEditor)
    if (parsed.componentes !== undefined || parsed.tuberias !== undefined) {
      return {
        componentes: parsed.componentes ?? [],
        tuberias: parsed.tuberias ?? [],
        zoom: parsed.zoom ?? 1,
        panX: parsed.panX ?? 0,
        panY: parsed.panY ?? 0,
      };
    }
    // Formato antiguo
    return {
      cards: parsed.cards ?? {},
      connections: parsed.connections ?? [],
    };
  } catch {
    return { cards: {}, connections: [] };
  }
}


// ── View sinóptico com layout do novo editor ──────────────────────────────────
const VIEW_W = 80;   // fallback para não-canónicos
const VIEW_H = 72;   // fallback para não-canónicos

function viewPortCenter(inst, port) {
  const shapeDef = getShapeDef(inst.componenteId);
  if (shapeDef) {
    const p = shapeDef.ports.find(pt => pt.id === port);
    if (p) return { x: inst.x + p.x, y: inst.y + p.y };
    const { width: W, height: H } = shapeDef;
    switch (port) {
      case 'left':   return { x: inst.x,         y: inst.y + H / 2 };
      case 'right':  return { x: inst.x + W,     y: inst.y + H / 2 };
      case 'top':    return { x: inst.x + W / 2, y: inst.y };
      case 'bottom': return { x: inst.x + W / 2, y: inst.y + H };
      default:       return { x: inst.x + W / 2, y: inst.y + H / 2 };
    }
  }
  switch (port) {
    case 'left':   return { x: inst.x,              y: inst.y + VIEW_H / 2 };
    case 'right':  return { x: inst.x + VIEW_W,     y: inst.y + VIEW_H / 2 };
    case 'top':    return { x: inst.x + VIEW_W / 2, y: inst.y };
    case 'bottom': return { x: inst.x + VIEW_W / 2, y: inst.y + VIEW_H };
    default:       return { x: inst.x + VIEW_W / 2, y: inst.y + VIEW_H / 2 };
  }
}

const ViewSinoptico = memo(function ViewSinoptico({ instances, connections, estado, alertas, modosLocais = {}, onComponenteClick, t }) {
  if (!instances.length) return null;

  // Bounding box para viewBox automático — respeta dimensiones variables por shape
  const pad = 30;
  let bMinX = Infinity, bMinY = Infinity, bMaxX = -Infinity, bMaxY = -Infinity;
  instances.forEach(inst => {
    const { width: W, height: H } = getShapeSize(inst.componenteId);
    if (inst.x - 16 < bMinX) bMinX = inst.x - 16;
    if (inst.y - 16 < bMinY) bMinY = inst.y - 16;
    if (inst.x + W + 16 > bMaxX) bMaxX = inst.x + W + 16;
    if (inst.y + H + 30 > bMaxY) bMaxY = inst.y + H + 30;  // +30 para label + valor
  });
  if (!isFinite(bMinX)) { bMinX = 0; bMinY = 0; bMaxX = 400; bMaxY = 300; }
  const vx = bMinX - pad;
  const vy = bMinY - pad;
  const vw = bMaxX - bMinX + 2 * pad;
  const vh = bMaxY - bMinY + 2 * pad;

  return (
    <svg
      viewBox={`${vx} ${vy} ${vw} ${vh}`}
      width="100%" height="100%"
      preserveAspectRatio="xMidYMid meet"
      className={s.svg}
    >
      <defs>
        {/* Flechas por tipo de tubería */}
        {PIPE_TYPE_KEYS.map(pt => (
          <marker key={pt} id={`viewArr_${pt}`} markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
            <path d="M0,0 L6,3 L0,6 Z" fill={PIPE_TYPES[pt].color} opacity="0.8" />
          </marker>
        ))}
      </defs>

      {/* Conexiones */}
      {connections.map((conn, i) => {
        const fromInst = instances.find(inst => inst.id === (conn.fromInstanceId ?? conn.from?.cardId));
        const toInst   = instances.find(inst => inst.id === (conn.toInstanceId   ?? conn.to?.cardId));
        if (!fromInst || !toInst) return null;
        const pt = resolvePipeType(conn);
        const cfg = PIPE_TYPES[pt];
        const p1 = viewPortCenter(fromInst, conn.fromPort ?? conn.from?.port ?? 'right');
        const p2 = viewPortCenter(toInst,   conn.toPort   ?? conn.to?.port   ?? 'left');
        const cx = (p1.x + p2.x) / 2;
        const d = `M ${p1.x} ${p1.y} C ${cx} ${p1.y}, ${cx} ${p2.y}, ${p2.x} ${p2.y}`;
        const dashVal = cfg.dasharray === 'none' ? undefined : cfg.dasharray;
        return (
          <g key={i}>
            {/* Sombra sutil */}
            <path d={d} fill="none"
              stroke={cfg.color}
              strokeWidth={cfg.width + 3}
              strokeDasharray={dashVal}
              opacity="0.10"
              pointerEvents="none"
            />
            {/* Tubería principal */}
            <path d={d} fill="none"
              stroke={cfg.color}
              strokeWidth={cfg.width}
              strokeDasharray={dashVal}
              markerEnd={`url(#viewArr_${pt})`}
              opacity="0.75"
            />
          </g>
        );
      })}

      {/* Instancias */}
      {instances.map(inst => {
        const alertState = getAlertState(inst.componenteId, alertas);
        const isManual = modosLocais[inst.componenteId] === 'MANUAL';
        const baseStroke = STATE_STROKE[alertState] ?? '#00c8e8';
        // En modo MANUAL, el contorno queda naranja para indicar control manual
        const stroke = isManual ? '#f5a623' : baseStroke;
        const fill = alertState === 'err'
          ? 'rgba(255,61,90,0.08)'
          : alertState === 'warn'
          ? 'rgba(245,166,35,0.08)'
          : isManual
          ? 'rgba(245,166,35,0.06)'
          : 'rgba(0,232,122,0.06)';

        const cfg = PRIMARY[inst.componenteId];
        const comp = estado?.componentes?.[inst.componenteId];
        let primary = null;
        if (comp && cfg) {
          const val = comp.valores?.[cfg.key];
          if (val != null) primary = `${val.toFixed(1)} ${cfg.unit}`;
        }

        const shapeDef = getShapeDef(inst.componenteId);
        const { width: shW, height: shH } = getShapeSize(inst.componenteId);

        const synKey = 'synoptic_label_' + inst.componenteId;
        const synTranslated = t(synKey);
        const paletKey = 'palette_' + inst.componenteId;
        const paletTranslated = t(paletKey);
        const displayLabel = synTranslated !== synKey
          ? synTranslated
          : paletTranslated !== paletKey
          ? paletTranslated
          : (inst.label ?? inst.componenteId);

        return (
          <g key={inst.id} transform={`translate(${inst.x},${inst.y})`}
            onClick={() => onComponenteClick?.(inst.componenteId)}
            style={{ cursor: 'pointer' }}>
            {shapeDef ? (
              /* Forma canónica: SVG nativo, sin rect de fondo */
              <>
                {renderShape(inst.componenteId, stroke, 1.8)}
                <text
                  x={shW / 2} y={shH + 14}
                  textAnchor="middle"
                  className={s.compLabel}
                  fill={stroke}
                >
                  {displayLabel}
                </text>
                {primary && (
                  <text
                    x={shW / 2} y={shH + 26}
                    textAnchor="middle"
                    className={s.compValue}
                    fill={stroke}
                  >
                    {primary}
                  </text>
                )}
                {isManual && (
                  <text x={shW / 2} y={shH + 38} textAnchor="middle"
                    style={{ fontSize: '11px', fontFamily: 'monospace', fill: '#f5a623', fontWeight: 700, letterSpacing: '1px' }}>
                    {t('manual_badge')}
                  </text>
                )}
              </>
            ) : (
              /* Fallback no canónico: icono inline escalado, sin rect de fondo */
              <>
                {(() => {
                  const sx = shW / 48;
                  const sy = shH / 48;
                  const compensatedSw = 1.8 / Math.min(sx, sy);
                  const item = PALETA_MAP[inst.componenteId];
                  return (
                    <g transform={`scale(${sx},${sy})`}>
                      {item
                        ? item.renderIcon(stroke, compensatedSw)
                        : <>
                            <rect x="4" y="4" width="40" height="40" rx="4" fill="none" stroke={stroke} strokeWidth={compensatedSw} />
                            <text x="24" y="30" textAnchor="middle" fontSize={18 / Math.min(sx, sy)} fontWeight="bold" fill={stroke} fontFamily="monospace">
                              {(inst.componenteId || '?')[0].toUpperCase()}
                            </text>
                          </>
                      }
                    </g>
                  );
                })()}
                <text x={shW / 2} y={shH + 14} textAnchor="middle"
                  className={s.compLabel} fill={stroke}>
                  {displayLabel}
                </text>
                {primary && (
                  <text x={shW / 2} y={shH + 26} textAnchor="middle"
                    className={s.compValue} fill={stroke}>
                    {primary}
                  </text>
                )}
                {isManual && (
                  <text x={shW / 2} y={shH + 38} textAnchor="middle"
                    style={{ fontSize: '11px', fontFamily: 'monospace', fill: '#f5a623', fontWeight: 700, letterSpacing: '1px' }}>
                    {t('manual_badge')}
                  </text>
                )}
              </>
            )}
          </g>
        );
      })}
    </svg>
  );
});

// ── Componente principal ──────────────────────────────────────────────────────
export default function Sinoptico({ projectId, estado, alertas = [], simulacaoAtiva = false }) {
  const [editMode, setEditMode] = useState(false);
  const [layout, setLayout] = useState(null);
  const [layoutLoaded, setLayoutLoaded] = useState(false);

  // Estado del panel de modo manual
  const [modoPanelComp, setModoPanelComp] = useState(null); // componenteId seleccionado

  // Caché local de modos (sobrescrito por el polling de /estado)
  const [modosLocais, setModosLocais] = useState({});

  const { t } = useLanguage();

  // Cargar layout guardado
  useEffect(() => {
    if (!projectId) return;
    api.get(`/api/proyectos/${projectId}/layout`)
      .then(r => {
        const parsed = parseLayout(r.data);
        setLayout(parsed);
      })
      .catch(() => { setLayout({ cards: {}, connections: [] }); })
      .finally(() => setLayoutLoaded(true));
  }, [projectId]);

  // Sincroniza modos locales con el estado proveniente del polling
  useEffect(() => {
    if (estado?.modoComponentes) {
      setModosLocais(prev => ({ ...prev, ...estado.modoComponentes }));
    }
  }, [estado?.modoComponentes]);

  // Callback llamado por ModoPanel al cambiar modo — actualiza estado local inmediatamente
  const handleModoChanged = useCallback((componenteId, novoModo) => {
    setModosLocais(prev => ({ ...prev, [componenteId]: novoModo }));
  }, []);

  // ── Callbacks del CanvasEditor ────────────────────────────────────────────
  function handleEditorSave(newLayout) {
    setLayout(newLayout);
    setEditMode(false);
  }

  function handleEditorCancel() {
    setEditMode(false);
  }

  // ── VIEW MODE — sin layout ─────────────────────────────────────────────────
  if (!layoutLoaded) {
    return <div className={s.wrap}><p className={s.hint}>{t('loading_synoptic')}</p></div>;
  }

  // ── EDIT MODE — CanvasEditor SVG ──────────────────────────────────────────
  if (editMode) {
    return (
      <CanvasEditor
        projectId={projectId}
        initialLayout={layout}
        estado={estado}
        alertas={alertas}
        onSave={handleEditorSave}
        onCancel={handleEditorCancel}
      />
    );
  }

  // ── VIEW MODE — sinóptico de lectura ──────────────────────────────────────
  // Determina si existe layout en el nuevo formato o en el antiguo
  const hasLayout = (
    (layout?.componentes?.length > 0) ||
    (layout?.cards && Object.keys(layout.cards).length > 0)
  );

  // Convierte instancias para renderizar en view mode
  const viewInstances = layout?.componentes ?? (
    layout?.cards
      ? Object.entries(layout.cards).map(([id, c]) => ({
          id, componenteId: c.type, x: c.x, y: c.y, label: c.type,
        }))
      : []
  );

  const viewConnections = layout?.tuberias ?? layout?.connections ?? [];

  // Componente abierto en el panel de modo
  const panelComp = modoPanelComp;
  const panelModo = panelComp ? (modosLocais[panelComp] ?? 'AUTO') : 'AUTO';
  const panelValores = panelComp ? (estado?.componentes?.[panelComp]?.valores ?? {}) : {};

  return (
    <div className={s.wrap}>
      {/* Toolbar view mode */}
      <div className={s.toolbar}>
        <span className={s.toolbarTitle}>{t('synoptic_title')}</span>
        {/* Badges de componentes em MANUAL */}
        {Object.entries(modosLocais)
          .filter(([, modo]) => modo === 'MANUAL')
          .map(([comp]) => (
            <span key={comp} className={s.badgeManual} title={`${t('synoptic_label_' + comp)} — ${t('manual_badge')}`}>
              {t('synoptic_label_' + comp)} — {t('manual_badge')}
            </span>
          ))}
        <button className={s.btnEdit} onClick={() => setEditMode(true)}>{t('edit_layout')}</button>
      </div>

      <div className={s.editorBody}>
        <div className={s.canvas}>
          {/* Canvas vacío: instrucción para construir el sinóptico */}
          {!hasLayout && (
            <div className={s.emptyState}>
              <p className={s.emptyStateTitle}>{t('synoptic_empty_title')}</p>
              <p className={s.emptyStateHint}>{t('synoptic_empty_hint')}</p>
            </div>
          )}

          {/* VIEW: sinóptico con instancias del nuevo editor */}
          {hasLayout && (
            <ViewSinoptico
              instances={viewInstances}
              connections={viewConnections}
              estado={estado}
              alertas={alertas}
              modosLocais={modosLocais}
              onComponenteClick={setModoPanelComp}
              t={t}
            />
          )}

          {/* Inactive overlay — mostrado cuando la simulación no está en ejecución */}
          {!simulacaoAtiva && (
            <div className={s.inactiveOverlay}>
              <span className={s.inactiveIcon}>◉</span>
              <p className={s.inactiveTitle}>{t('simulation_inactive')}</p>
              <p className={s.inactiveMsg}>{t('simulation_inactive_msg')}</p>
            </div>
          )}
        </div>
      </div>

      {/* Panel de modo manual — se abre sobre el sinóptico */}
      {panelComp && (
        <ModoPanel
          projectId={projectId}
          componenteId={panelComp}
          modoAtual={panelModo}
          valoresAtuais={panelValores}
          onClose={() => setModoPanelComp(null)}
          onModoChanged={handleModoChanged}
        />
      )}
    </div>
  );
}
