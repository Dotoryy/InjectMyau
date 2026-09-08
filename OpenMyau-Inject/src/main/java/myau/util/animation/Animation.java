package myau.util.animation;

public abstract class Animation {
    private long lastMs = System.currentTimeMillis();
    protected int duration;
    protected double endPoint;
    protected Direction direction;

    public Animation(int ms, double endPoint) {
        this(ms, endPoint, Direction.FORWARDS);
    }

    public Animation(int ms, double endPoint, Direction direction) {
        this.duration = ms;
        this.endPoint = endPoint;
        this.direction = direction;
    }

    protected long elapsed() {
        return System.currentTimeMillis() - this.lastMs;
    }

    public void reset() {
        this.lastMs = System.currentTimeMillis();
    }

    public boolean isDone() {
        return this.elapsed() > this.duration;
    }

    public boolean finished(Direction direction) {
        return this.isDone() && this.direction == direction;
    }

    public double getEndPoint() {
        return this.endPoint;
    }

    public void setEndPoint(double endPoint) {
        this.endPoint = endPoint;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getDuration() {
        return this.duration;
    }

    public Direction getDirection() {
        return this.direction;
    }

    public void changeDirection() {
        this.setDirection(this.direction.opposite());
    }

    public Animation setDirection(Direction direction) {
        if (this.direction != direction) {
            this.direction = direction;
            long travelled = Math.min(this.duration, this.elapsed());
            this.lastMs = System.currentTimeMillis() - (this.duration - travelled);
        }
        return this;
    }

    protected boolean correctOutput() {
        return false;
    }

    public double getOutput() {
        if (this.direction.forwards()) {
            if (this.isDone()) {
                return this.endPoint;
            }
            return this.getEquation(this.elapsed() / (double) this.duration) * this.endPoint;
        }
        if (this.isDone()) {
            return 0.0;
        }
        if (this.correctOutput()) {
            double reverseTime = Math.min(this.duration, Math.max(0, this.duration - this.elapsed()));
            return this.getEquation(reverseTime / (double) this.duration) * this.endPoint;
        }
        return (1.0 - this.getEquation(this.elapsed() / (double) this.duration)) * this.endPoint;
    }

    protected abstract double getEquation(double x);
}
