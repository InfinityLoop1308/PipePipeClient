import openai
import json
from forbiddenfruit import curse
import sys
import config
from concurrent.futures import ThreadPoolExecutor

def filter_list(self, func):
    """
    Filter the list based on a given lambda function.

    Parameters:
    func (function): The lambda function used for filtering.

    Returns:
    list: The filtered list.
    """
    return list(filter(func, self))


def map_list(self, func):
    """
    Map the list based on a given lambda function.

    Parameters:
    func (function): The lambda function used for mapping.

    Returns:
    list: The mapped list.
    """
    return list(map(func, self))


def filter_dict(self, func):
    """
    Filter the dictionary based on a given lambda function operating on key-value pairs.

    Parameters:
    func (function): The lambda function used for filtering (takes key and value as input).

    Returns:
    dict: The filtered dictionary.
    """
    return {k: v for k, v in self.items() if func(k, v)}


# Use forbiddenfruit to add the filter_dict method to the built-in dict class
curse(list, "filter", filter_list)
curse(list, "map", map_list)
curse(dict, "filter", filter_dict)


class Translator:
    def __init__(self):
        self.client = openai.OpenAI(api_key=config.api_key, base_url=config.base_url)

    def translate(self, content, language):
        response = self.client.chat.completions.create(
            model="gpt-4o-2024-08-06",
            response_format={"type": "json_object"},
            messages=[
                {
                    "role": "user",
                    "content": f"Translate the values(not including the keys) in the dict to values-{language}. Always preserve new line tokens like \\n. Output result in json format. \n\n{content}",
                }
            ],
            temperature=0,
        )
        translated_text = response.choices[0].message.content.strip()
        # print(translated_text)
        return translated_text

    def get_translated_dict(self, content, language):
        return json.loads(self.translate(content, language))


from bs4 import BeautifulSoup


class XMLHandler:
    def __init__(self, file_path=None):
        self.file_path = file_path
        self.soup = None
        if file_path:
            self.load_from_file(file_path)

    def load_from_file(self, file_path):
        with open(file_path, 'r') as file:
            self.soup = BeautifulSoup(file, 'xml')
        self.file_path = file_path

    def add_entry(self, parent_selector, new_tag, attributes=None, text=None):
        parent = self.soup.select_one(parent_selector)
        if parent is None:
            raise Exception(f"The parent with selector {parent_selector} does not exist.")
        new_element = self.soup.new_tag(new_tag)
        if attributes:
            new_element.attrs = attributes
        if text:
            new_element.string = text
        parent.append(new_element)
        parent.append("\n")

    def update_entry(self, selector, new_text=None):
        element = self.soup.select_one(selector)
        if element is None:
            raise Exception(f"The element with selector {selector} does not exist.")
        if new_text is not None:
            element.string = new_text

    def delete_entry(self, selector):
        element = self.soup.select_one(selector)
        if element:
            element.decompose()

    def write_to_file(self, file_path=None):
        if file_path is None:
            if self.file_path:
                file_path = self.file_path
            else:
                raise Exception("No file path specified for writing XML data.")
        with open(file_path, 'w') as file:
            file.write(str(self.soup))

    def load_strings_to_dict(self, tail_length=0):
        strings_dict = {}
        string_tags = self.soup.find_all('string')
        for tag in string_tags:
            name = tag.get('name')
            if name:
                strings_dict[name] = tag.text
        return dict(list(strings_dict.items())[-1 * tail_length:])

    def load_strings_to_dict_by_part(self):
        strings_dict = {}
        string_tags = self.soup.find_all('string')
        result = []
        start = 0
        while start < len(string_tags):
            for tag in string_tags:
                name = tag.get('name')
                if name:
                    strings_dict[name] = tag.text
            result.append(dict(list(strings_dict.items())[start:start + 100]))
            start += 100
        return result

def escape(text):
    result = ''
    i = 0
    while i < len(text):
        if text[i] == '\\' and i + 1 < len(text) and text[i + 1] == "'":
            result += "\\'"
            i += 2
        elif text[i] == "'":
            result += "\\'"
            i += 1
        else:
            result += text[i]
            i += 1
    return result.replace("\n", "\\n")


class StringTranslator:

    def __init__(self):
        self.base = XMLHandler('app/src/main/res/values/strings.xml')
        self.targets = [
            XMLHandler('app/src/main/res/values-zh-rCN/strings.xml'),
            XMLHandler('app/src/main/res/values-zh-rTW/strings.xml'),
            XMLHandler('app/src/main/res/values-ja/strings.xml'),
            XMLHandler('app/src/main/res/values-vi/strings.xml'),
            XMLHandler('app/src/main/res/values-fr/strings.xml'),
            XMLHandler('app/src/main/res/values-de/strings.xml'),
            XMLHandler('app/src/main/res/values-it/strings.xml'),
            XMLHandler('app/src/main/res/values-es/strings.xml'),
        ]
        self.translator = Translator()

    def translate_latest_updates_to_all(self, length, ignore_update=False):
        updates = self.base.load_strings_to_dict(tail_length=int(length))
        def translate_and_update_target(target):
            result = self.translator.get_translated_dict(updates, target.file_path.split('/')[-2][7:])
            if ignore_update:
                print(result)
                return
            for key, value in result.items():
                try :
                    target.update_entry(f'string[name="{key}"]', escape(value))
                except:
                    target.add_entry('resources', 'string', {'name': key}, escape(value))
            target.write_to_file()
            # Process translations in parallel
        with ThreadPoolExecutor() as executor:
            executor.map(translate_and_update_target, self.targets)

    def translate_everything(self):
        data = self.base.load_strings_to_dict_by_part()
        for target in self.targets:
            for updates in data:
                result = self.translator.get_translated_dict(updates, target.file_path.split('/')[-2][7:])
                for key, value in result.items():
                    target.add_entry('resources', 'string', {'name': key}, escape(value))
            target.write_to_file()

    def translate_item_updates_to_all(self, item_list):
        updates = self.base.load_strings_to_dict().filter(lambda k, v: k in item_list)

        def translate_and_update_target(target):
            result = self.translator.get_translated_dict(updates, target.file_path.split('/')[-2][7:])
            for key, value in result.items():
                try :
                    target.update_entry(f'string[name="{key}"]', escape(value))
                except:
                    target.add_entry('resources', 'string', {'name': key}, escape(value))
            target.write_to_file()

        # Process translations in parallel
        with ThreadPoolExecutor() as executor:
            executor.map(translate_and_update_target, self.targets)


    def add_new_entry(self, name, value):
        self.base.add_entry('resources', 'string', {'name': name}, value)
        self.base.write_to_file()
        updates = {name: value}

        def translate_and_update(target):
            result = self.translator.get_translated_dict(updates, target.file_path.split('/')[-2][7:])
            target.add_entry('resources', 'string', {'name': name}, escape(result[name]))
            target.write_to_file()

            # Process translations in parallel
        with ThreadPoolExecutor() as executor:
            executor.map(translate_and_update, self.targets)

    def translate_new_entries(self, item_list):
        updates = self.base.load_strings_to_dict().filter(lambda k, v: k in item_list)
        for target in self.targets:
            result = self.translator.get_translated_dict(updates, target.file_path.split('/')[-2][7:])
            for key, value in result.items():
                target.add_entry('resources', 'string', {'name': key}, escape(value))
            target.write_to_file()

    def delete_entry(self, name):
        self.base.delete_entry(f'string[name="{name}"]')
        self.base.write_to_file()
        for target in self.targets:
            target.delete_entry(f'string[name="{name}"]')
            target.write_to_file()


if __name__ == '__main__':
    translator = StringTranslator()
    # read params
    args = sys.argv
    if args[1] == 'add':
        translator.add_new_entry(args[2], args[3])
    elif args[1] == 'delete' or args[1] == 'remove':
        translator.delete_entry(args[2])
    elif args[1] == 'update':
        translator.translate_item_updates_to_all(args[2:])
    elif args[1] == 'add_multi':
        translator.translate_new_entries(args[2:])
    elif args[1] == 'translate':
        translator.translate_everything()
    else:
        translator.translate_latest_updates_to_all(args[1])
