if [ ! -f cfr.jar ]; then
    echo "Downloading CFR decompiler..."
    curl -L -o cfr.jar https://www.benf.org/other/cfr/cfr-0.152.jar
fi

find ../ -name "*.class" | while read class_file; do
    # Get the filename without extension
    filename=$(basename "$class_file" .class)
    
    # Get the path relative to ../
    relative_path=$(dirname "${class_file#../}")
    
    # Run the decompiler
    java -jar cfr.jar "$class_file" --outputdir "./decompiled/$relative_path"
done

rm -f cfr.jar