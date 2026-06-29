package net.runelite.client.plugins.microbot.util.mouse.mousev2;

/**
 * A 2D cubic Bézier segment, based on the upstream {@code TBd7}. Used as the building block of the
 * {@link NaturalCubicSpline} that shapes a mouse stroke: the natural-cubic-spline fit produces one
 * of these per interval between control points.
 */
final class CubicBezier {
    private final Vec2 p0;
    private final Vec2 c1;
    private final Vec2 c2;
    private final Vec2 p3;

    /** 5-point Gauss-Legendre quadrature nodes/weights on [0,1] for arc-length integration (TBd7.TBk). */
    private static final double[] GL_NODES = {0.046910077, 0.2307653449, 0.5, 0.7692346551, 0.953089923};
    private static final double[] GL_WEIGHTS = {0.1184634425, 0.2393143352, 0.2844444444, 0.2393143352, 0.1184634425};

    CubicBezier(Vec2 p0, Vec2 c1, Vec2 c2, Vec2 p3) {
        this.p0 = p0;
        this.c1 = c1;
        this.c2 = c2;
        this.p3 = p3;
    }

    /** Point on the curve at parameter {@code t} in [0,1] (Bernstein form, TBd7.TBu). */
    Vec2 point(double t) {
        double u = 1.0 - t;
        double u2 = u * u;
        double u3 = u2 * u;
        double t2 = t * t;
        double t3 = t2 * t;
        return p0.scale(u3)
                .add(c1.scale(3.0 * u2 * t))
                .add(c2.scale(3.0 * u * t2))
                .add(p3.scale(t3));
    }

    /** First derivative (tangent) at parameter {@code t} (TBd7.TBe). */
    Vec2 derivative(double t) {
        double u = 1.0 - t;
        return c1.sub(p0).scale(3.0 * u * u)
                .add(c2.sub(c1).scale(6.0 * u * t))
                .add(p3.sub(c2).scale(3.0 * t * t));
    }

    /** Arc length over the sub-interval {@code [t1, t2]} via 5-point Gauss-Legendre (TBd7.TBk). */
    double arcLength(double t1, double t2) {
        double span = t2 - t1;
        double sum = 0.0;
        for (int i = 0; i < GL_NODES.length; i++) {
            double t = t1 + GL_NODES[i] * span;
            sum += GL_WEIGHTS[i] * derivative(t).length();
        }
        return sum * span;
    }
}
