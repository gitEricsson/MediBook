import org.testcontainers.DockerClientFactory;
public class TestTc {
    public static void main(String[] args) {
        try {
            System.out.println(DockerClientFactory.instance().client().pingCmd().exec());
            System.out.println(" Docker connected!\);
