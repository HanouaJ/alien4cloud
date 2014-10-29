package alien4cloud.tosca.parser;

import lombok.Getter;

@Getter
public class MappingTarget {
    private boolean isRootPath;
    private String path;
    private IParser parser;

    public MappingTarget(String path, IParser parser) {
        this.isRootPath = path.startsWith("/");
        if (isRootPath) {
            this.path = path.substring(1);
        } else {
            this.path = path;
        }
        this.parser = parser;
    }
}