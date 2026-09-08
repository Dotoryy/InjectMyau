package myau.util.animation;

public class ContinualAnimation {
    private float output;
    private float endpoint;
    private Animation animation = new SmoothStepAnimation(0, 0.0, Direction.BACKWARDS);

    public void animate(float destination, int ms) {
        this.output = this.endpoint - (float) this.animation.getOutput();
        this.endpoint = destination;
        if (this.output != this.endpoint - destination) {
            this.animation = new SmoothStepAnimation(ms, this.endpoint - this.output,
                    Direction.BACKWARDS);
        }
    }

    public boolean isDone() {
        return this.output == this.endpoint || this.animation.isDone();
    }

    public Animation getAnimation() {
        return this.animation;
    }

    public float getOutput() {
        this.output = this.endpoint - (float) this.animation.getOutput();
        return this.output;
    }
}
