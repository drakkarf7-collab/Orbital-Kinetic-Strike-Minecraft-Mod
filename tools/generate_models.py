"""Generator for Wavefront OBJ models of the orbital ring and tungsten lance."""

from __future__ import annotations

import math
from pathlib import Path

ROOT = (
    Path(__file__).resolve().parent.parent
    / "src"
    / "main"
    / "resources"
    / "assets"
    / "orbital_strike"
    / "models"
    / "mesh"
)


class Obj:
    """Minimal Wavefront OBJ builder with vertices, analytical normals, and groups."""

    def __init__(self) -> None:
        self.v: list[tuple[float, float, float]] = []
        self.vn: list[tuple[float, float, float]] = []
        self.groups: dict[str, list[tuple[tuple[int, int], ...]]] = {}

    def _v(self, p: tuple[float, float, float]) -> int:
        self.v.append(p)
        return len(self.v)

    def _vn(self, n: tuple[float, float, float]) -> int:
        lx, ly, lz = n
        m = math.sqrt(lx * lx + ly * ly + lz * lz) or 1.0
        self.vn.append((lx / m, ly / m, lz / m))
        return len(self.vn)

    def _face(self, group: str, pts: list[tuple[float, float, float]], norm: tuple[float, float, float] | None = None) -> None:
        if norm is None:
            ax, ay, az = pts[1][0] - pts[0][0], pts[1][1] - pts[0][1], pts[1][2] - pts[0][2]
            bx, by, bz = pts[2][0] - pts[0][0], pts[2][1] - pts[0][1], pts[2][2] - pts[0][2]
            norm = (ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
        ni = self._vn(norm)
        indices = tuple((self._v(p), ni) for p in pts)
        self.groups.setdefault(group, []).append(indices)

    def quad(
        self,
        group: str,
        p0: tuple[float, float, float],
        p1: tuple[float, float, float],
        p2: tuple[float, float, float],
        p3: tuple[float, float, float],
    ) -> None:
        self._face(group, [p0, p1, p2, p3])

    def box(
        self,
        group: str,
        center: tuple[float, float, float],
        half: tuple[float, float, float],
    ) -> None:
        cx, cy, cz = center
        hx, hy, hz = half
        x0, x1 = cx - hx, cx + hx
        y0, y1 = cy - hy, cy + hy
        z0, z1 = cz - hz, cz + hz
        self.quad(group, (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (x1, y0, z1))
        self.quad(group, (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (x0, y0, z0))
        self.quad(group, (x0, y1, z0), (x0, y1, z1), (x1, y1, z1), (x1, y1, z0))
        self.quad(group, (x0, y0, z1), (x0, y0, z0), (x1, y0, z0), (x1, y0, z1))
        self.quad(group, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1))
        self.quad(group, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0))

    def prism(
        self,
        group: str,
        x0: float,
        x1: float,
        r0: float,
        r1: float,
        sides: int = 8,
        phase: float = 0.0,
        cap_start: bool = True,
        cap_end: bool = True,
    ) -> None:
        ring0 = [
            (
                x0,
                r0 * math.cos(2.0 * math.pi * i / sides + phase),
                r0 * math.sin(2.0 * math.pi * i / sides + phase),
            )
            for i in range(sides)
        ]
        ring1 = [
            (
                x1,
                r1 * math.cos(2.0 * math.pi * i / sides + phase),
                r1 * math.sin(2.0 * math.pi * i / sides + phase),
            )
            for i in range(sides)
        ]
        for i in range(sides):
            j = (i + 1) % sides
            self.quad(group, ring0[i], ring1[i], ring1[j], ring0[j])
        if cap_start and r0 > 1e-4:
            self._face(group, list(reversed(ring0)), (-1.0, 0.0, 0.0))
        if cap_end and r1 > 1e-4:
            self._face(group, ring1, (1.0, 0.0, 0.0))

    def ring_collar(
        self,
        group: str,
        x0: float,
        x1: float,
        r_in: float,
        r_out: float,
        sides: int = 16,
        phase: float = 0.0,
    ) -> None:
        """Hollow annular collar around the X axis."""
        for i in range(sides):
            a0 = 2.0 * math.pi * i / sides + phase
            a1 = 2.0 * math.pi * ((i + 1) % sides) / sides + phase
            c0, s0 = math.cos(a0), math.sin(a0)
            c1, s1 = math.cos(a1), math.sin(a1)
            # Outer face
            self.quad(
                group,
                (x0, c0 * r_out, s0 * r_out),
                (x1, c0 * r_out, s0 * r_out),
                (x1, c1 * r_out, s1 * r_out),
                (x0, c1 * r_out, s1 * r_out),
            )
            # Inner face (normal pointing toward axis)
            self.quad(
                group,
                (x0, c1 * r_in, s1 * r_in),
                (x1, c1 * r_in, s1 * r_in),
                (x1, c0 * r_in, s0 * r_in),
                (x0, c0 * r_in, s0 * r_in),
            )
            # Front and rear end caps
            self.quad(
                group,
                (x1, c0 * r_in, s0 * r_in),
                (x1, c1 * r_in, s1 * r_in),
                (x1, c1 * r_out, s1 * r_out),
                (x1, c0 * r_out, s0 * r_out),
            )
            self.quad(
                group,
                (x0, c0 * r_out, s0 * r_out),
                (x0, c1 * r_out, s1 * r_out),
                (x0, c1 * r_in, s1 * r_in),
                (x0, c0 * r_in, s0 * r_in),
            )

    def save(self, name: str, comment: str) -> int:
        ROOT.mkdir(parents=True, exist_ok=True)
        path = ROOT / name
        lines = [f"# {comment}"]
        for x, y, z in self.v:
            lines.append(f"v {x:.5f} {y:.5f} {z:.5f}")
        for x, y, z in self.vn:
            lines.append(f"vn {x:.5f} {y:.5f} {z:.5f}")
        for g, faces in self.groups.items():
            lines.append(f"g {g}")
            for face in faces:
                toks = " ".join(f"{vi}//{ni}" for vi, ni in face)
                lines.append(f"f {toks}")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return len(lines)


def ring_module() -> None:
    """
    Clean circular gates joined by a continuous dorsal rail:
    - 3 smooth circular collars per module (spaced to leave Jupiter and stars visible);
    - Continuous twin lower rails (-Y) and outer dorsal rail (+Z) with no visible seam;
    - Thin internal emissive rings and ladder rungs (cyan/white coils).
    """
    o = Obj()
    # Continuous half-span (12.6 / 2 / 0.86 = 7.3256) for flush contact between modules.
    half_span = 7.326
    gate_xs = (-4.88, 0.0, 4.88)

    # 1. Three thin circular gates per module (smooth collar, open space between rings).
    for gx in gate_xs:
        o.ring_collar("body_gate", gx - 0.20, gx + 0.20, r_in=1.20, r_out=1.38, sides=20)
        o.ring_collar("body_trim", gx - 0.26, gx - 0.20, r_in=1.24, r_out=1.33, sides=20)
        o.ring_collar("body_trim", gx + 0.20, gx + 0.26, r_in=1.24, r_out=1.33, sides=20)
        # Internal coil ring recessed inside each gate.
        o.ring_collar("emissive_coil", gx - 0.08, gx + 0.08, r_in=1.185, r_out=1.205, sides=20)

    # 2. Continuous longitudinal beams: twin rails under the bore (-Y) and dorsal spine (+Z).
    o.box("body_spine", (0.0, -1.42, -0.48), (half_span, 0.065, 0.075))
    o.box("body_spine", (0.0, -1.42, 0.48), (half_span, 0.065, 0.075))
    o.box("body_spine", (0.0, 0.0, 1.44), (half_span, 0.06, 0.075))
    o.box("body_spine", (0.0, 1.40, 0.0), (half_span, 0.05, 0.065))

    # Thin crossbars between the two lower rails aligned with the gates.
    for gx in gate_xs:
        o.box("body_truss", (gx, -1.40, 0.0), (0.18, 0.05, 0.52))
        o.box("body_truss", (gx, -1.29, -0.48), (0.12, 0.11, 0.05))
        o.box("body_truss", (gx, -1.29, 0.48), (0.12, 0.11, 0.05))
        o.box("body_truss", (gx, 0.0, 1.38), (0.18, 0.09, 0.07))

    # 3. Guide rails and very fine internal luminous ladder (reads smooth from afar, crisp up close).
    for ang_deg in (0.0, 90.0, 180.0, 270.0):
        rad = math.radians(ang_deg)
        ry, rz = math.cos(rad) * 1.21, math.sin(rad) * 1.21
        o.box("emissive_coil", (0.0, ry, rz), (half_span, 0.020, 0.020))

    # Small thin transverse rungs along the lower bore (-Y).
    for i in range(12):
        rx = -half_span + (i + 0.5) * (2.0 * half_span / 12.0)
        o.box("emissive_coil", (rx, -1.18, 0.0), (0.032, 0.014, 0.34))

    # Ultra-thin continuous trim lines on the lower and side rails for a clean circle at distance.
    o.box("emissive_coil", (0.0, -1.35, -0.48), (half_span, 0.024, 0.026))
    o.box("emissive_coil", (0.0, -1.35, 0.48), (half_span, 0.024, 0.026))
    o.box("emissive_coil", (0.0, 0.0, 1.46), (half_span, 0.022, 0.022))

    faces = o.save("ring_module.obj", "Clean circular-gate ring module")
    print("ring_module.obj:", faces, "lines")


def needle() -> None:
    """
    Tapered arrow-shaped kinetic lance:
    - Octagonal tapered nose (+3.4) and long dark fuselage (-25.0 .. +1.8);
    - Thin emissive wireframe lines along the hull and rings;
    - 4 large swept-back delta wings with emissive leading edges;
    - Compact cruciform rear nozzle.
    """
    o = Obj()
    sides = 8
    phase = math.pi / 8.0
    tip_x = 3.40
    collar_x = 1.65
    mid_x = -14.80
    tail_x = -25.00

    # Emissive tapered tip (kept to the very tip to avoid saturating bloom).
    o.prism("emissive_tip", collar_x, tip_x, 0.16, 0.015, sides=sides, phase=phase, cap_start=False)

    # Forward nose + long dark tungsten main fuselage.
    o.prism("body_hull", -3.20, collar_x, 0.50, 0.16, sides=sides, phase=phase)
    o.prism("body_hull", mid_x, -3.20, 0.44, 0.50, sides=sides, phase=phase)
    o.prism("body_hull", tail_x, mid_x, 0.46, 0.44, sides=sides, phase=phase)
    # Reinforcement collar right ahead of the delta-wing root.
    o.prism("body_hull", mid_x - 0.90, mid_x + 0.10, 0.53, 0.50, sides=sides, phase=phase)

    # Thin cyan longitudinal seams along cardinal edges (crisp wireframe look).
    for ang_deg in (0.0, 90.0, 180.0, 270.0):
        rad = math.radians(ang_deg)
        cy, cz = math.cos(rad) * 0.47, math.sin(rad) * 0.47
        o.box("emissive_seam", (-14.2, cy, cz), (10.6, 0.022, 0.022))

    # Thin emissive rings spaced along the hull.
    for rx, rr in ((-1.0, 0.34), (-5.0, 0.49), (-9.5, 0.47), (-14.0, 0.455), (-18.5, 0.465), (-23.0, 0.465)):
        o.ring_collar("emissive_seam", rx - 0.045, rx + 0.045, r_in=rr - 0.01, r_out=rr + 0.018, sides=sides, phase=phase)

    # 4 large strongly swept-back cruciform delta wings (visible during chase and reentry).
    root_fwd = -14.80
    root_aft = -24.50
    tip_fwd = -23.10
    tip_aft = -24.85
    root_r = 0.42
    span = 3.25
    thick = 0.055

    for idx in range(4):
        ang = math.radians(idx * 90.0)
        ey, ez = math.cos(ang), math.sin(ang)
        ty, tz = -ez * thick, ey * thick

        def pt(x: float, r: float, side: float) -> tuple[float, float, float]:
            return (x, ey * r + ty * side, ez * r + tz * side)

        # Upper/lower wing panels
        o.quad("body_wing", pt(root_fwd, root_r, 1), pt(tip_fwd, span, 0.25), pt(tip_aft, span, 0.25), pt(root_aft, root_r, 1))
        o.quad("body_wing", pt(root_aft, root_r, -1), pt(tip_aft, span, -0.25), pt(tip_fwd, span, -0.25), pt(root_fwd, root_r, -1))
        # Leading edge and wingtip
        o.quad("body_wing", pt(root_fwd, root_r, -1), pt(tip_fwd, span, -0.25), pt(tip_fwd, span, 0.25), pt(root_fwd, root_r, 1))
        o.quad("body_wing", pt(tip_fwd, span, -0.25), pt(tip_aft, span, -0.25), pt(tip_aft, span, 0.25), pt(tip_fwd, span, 0.25))
        o.quad("body_wing", pt(root_aft, root_r, 1), pt(tip_aft, span, 0.25), pt(tip_aft, span, -0.25), pt(root_aft, root_r, -1))

        # Thin cyan emissive trim along each delta wing's leading edge.
        steps = 8
        for s in range(steps):
            u0, u1 = s / steps, (s + 1) / steps
            x_a = root_fwd + (tip_fwd - root_fwd) * u0
            x_b = root_fwd + (tip_fwd - root_fwd) * u1
            r_a = root_r + (span - root_r) * u0
            r_b = root_r + (span - root_r) * u1
            mx, mr = 0.5 * (x_a + x_b), 0.5 * (r_a + r_b)
            # Small tangentially oriented segment approximated as a quad along the leading edge.
            o.quad(
                "emissive_seam",
                (x_a + 0.03, ey * r_a + ty * 0.6, ez * r_a + tz * 0.6),
                (x_b + 0.03, ey * r_b + ty * 0.6, ez * r_b + tz * 0.6),
                (x_b - 0.04, ey * r_b - ty * 0.6, ez * r_b - tz * 0.6),
                (x_a - 0.04, ey * r_a - ty * 0.6, ez * r_a - tz * 0.6),
            )
            _ = (mx, mr)

    # Compact rear nozzle and thin exhaust cross (no bulky square block).
    o.prism("emissive_tail", tail_x - 0.10, tail_x + 0.10, 0.24, 0.30, sides=sides, phase=phase)
    o.box("emissive_tail", (tail_x - 0.03, 0.0, 0.0), (0.04, 0.78, 0.026))
    o.box("emissive_tail", (tail_x - 0.03, 0.0, 0.0), (0.04, 0.026, 0.78))

    faces = o.save("needle.obj", "Octagonal kinetic lance with 4 delta wings")
    print("needle.obj:", faces, "lines")


if __name__ == "__main__":
    ring_module()
    needle()
    print("Models generated in", ROOT)
